package com.melody.mcagent.rt.perception;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.melody.mcagent.rt.action.Containers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/**
 * Builds the text a bot "sees", under the project's realism rules.
 *
 * <p>Everything reported here has passed the {@link Perception} line-of-sight test, except the
 * bot's own body and inventory, which a person obviously knows without looking. Container contents
 * are included only for containers the bot has actually opened.
 *
 * <p>Output is deliberately compact and aggregated. A raw block dump would be both enormous and
 * useless to a language model; what matters is a short, legible summary of the situation.
 */
public final class ObservationBuilder {

    /** Blocks are aggregated into these categories rather than listed individually. */
    private static final int MAX_NAMED_BLOCKS = 16;
    private static final int MAX_ENTITIES = 12;
    private static final int MAX_INVENTORY_LINES = 20;

    private ObservationBuilder() {
    }

    /**
     * A one-paragraph description of what the bot can perceive, suitable for an LLM prompt.
     *
     * @param radius how far to scan for blocks; scanning is expensive, so callers should keep this
     *               modest and refresh on a schedule rather than every tick
     */
    public static String describe(ServerPlayer bot, int radius) {
        return describe(bot, radius, false);
    }

    /** Build a state snapshot, optionally listing every stack for a chat-triggered turn. */
    public static String describe(ServerPlayer bot, int radius, boolean completeInventory) {
        StringBuilder sb = new StringBuilder();

        // Addressed chat goes first. A model reading a long observation is most influenced by its
        // opening and closing lines, and the chat section alone (at the bottom) proved too easy to
        // skim past: a player asked the bot a direct question and got an unrelated remark back.
        String banner = ChatLog.directAddressBanner(bot);
        if (banner != null) {
            sb.append(banner).append("\n\n");
        }
        // Right below the message it applies to: what the bot has already said about it. Three
        // near-identical acknowledgements of one instruction is the failure this prevents.
        String said = ChatLog.repetitionWarning(bot);
        if (said != null) {
            sb.append(said).append("\n\n");
        }

        sb.append("=== YOUR STATE ===\n");
        sb.append(describeSelf(bot)).append('\n');

        sb.append("\n=== WHAT YOU CAN SEE ===\n");
        sb.append(describeSurroundings(bot, radius)).append('\n');

        sb.append("\n=== INVENTORY ===\n");
        sb.append(describeInventory(bot,
                completeInventory ? Integer.MAX_VALUE : MAX_INVENTORY_LINES)).append('\n');

        // Speech is input as well as output. Putting it last keeps it adjacent to the model's next
        // decision, and marking what is new lets it tell a fresh question from old chatter.
        sb.append("\n=== CHAT YOU CAN HEAR ===\n");
        sb.append(ChatLog.describe(bot)).append('\n');

        return sb.toString();
    }

    /** The bot's own condition: a player always knows this about themselves. */
    public static String describeSelf(ServerPlayer bot) {
        Vec3 pos = bot.position();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Position: %.1f, %.1f, %.1f%n", pos.x, pos.y, pos.z));
        sb.append("Dimension: ").append(bot.serverLevel().dimension().location()).append('\n');
        sb.append(String.format("Health: %.0f/%.0f  Food: %d/20%n",
                bot.getHealth(), bot.getMaxHealth(), bot.getFoodData().getFoodLevel()));

        long dayTime = Math.floorMod(bot.serverLevel().getDayTime(), 24000L);
        String partOfDay = dayTime < 1000L ? "sunrise"
                : dayTime < 12000L ? "day"
                : dayTime < 13000L ? "sunset"
                : dayTime < 23000L ? "night" : "dawn";
        String weather = bot.serverLevel().isThundering() ? "thunderstorm"
                : bot.serverLevel().isRaining() ? "rain" : "clear";
        BlockPos feet = bot.blockPosition();
        String biome = bot.serverLevel().getBiome(feet).unwrapKey()
                .map(key -> key.location().toString()).orElse("unknown");
        sb.append("Time: ").append(partOfDay).append(" (day tick ").append(dayTime)
          .append(")  Weather: ").append(weather).append('\n');
        sb.append("Facing: ").append(bot.getDirection().getName())
          .append(String.format(" (yaw %.0f, pitch %.0f)%n", bot.getYRot(), bot.getXRot()));
        sb.append("Biome: ").append(biome)
          .append("  Light: block ").append(bot.serverLevel().getBrightness(LightLayer.BLOCK, feet))
          .append("/15, sky ").append(bot.serverLevel().getBrightness(LightLayer.SKY, feet))
          .append("/15\n");
        sb.append("Armor: ").append(bot.getArmorValue())
          .append("  XP level: ").append(bot.experienceLevel).append('\n');

        if (bot.getAirSupply() < bot.getMaxAirSupply()) {
            sb.append("Air: ").append(bot.getAirSupply()).append("/").append(bot.getMaxAirSupply()).append('\n');
        }

        if (bot.isOnFire()) {
            sb.append("You are ON FIRE.\n");
        }
        if (bot.isInWater()) {
            sb.append("You are in water.\n");
        }
        if (!bot.onGround()) {
            sb.append("You are airborne.\n");
        }
        if (!bot.getActiveEffects().isEmpty()) {
            sb.append("Effects:");
            bot.getActiveEffects().forEach(effect -> sb.append(' ')
                    .append(effect.getEffect().value().getDisplayName().getString())
                    .append(' ').append(effect.getAmplifier() + 1));
            sb.append('\n');
        }

        ItemStack held = bot.getMainHandItem();
        sb.append("Holding: ").append(describeEquippedStack(held));
        sb.append(" (hotbar slot ").append(bot.getInventory().selected).append(")\n");

        ItemStack offhand = bot.getOffhandItem();
        if (!offhand.isEmpty()) {
            sb.append("Offhand: ").append(describeEquippedStack(offhand)).append('\n');
        }

        List<String> worn = new ArrayList<>();
        for (EquipmentSlot slot : new EquipmentSlot[] {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
            ItemStack stack = bot.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                worn.add(slot.getName() + "=" + describeEquippedStack(stack));
            }
        }
        if (!worn.isEmpty()) {
            sb.append("Wearing: ").append(String.join(", ", worn)).append('\n');
        }

        return sb.toString();
    }

    /** Nearby blocks and entities, filtered by line of sight. */
    public static String describeSurroundings(ServerPlayer bot, int radius) {
        StringBuilder sb = new StringBuilder();

        // Geometry comes before the long object list. It is deterministic and compact: the model
        // should know it is in a closed tunnel with one frontier without reverse-engineering that
        // fact from grouped stone coordinates.
        sb.append(SemanticScene.describe(bot));

        List<Perception.SeenEntity> entities = Perception.visibleEntities(bot);
        if (entities.isEmpty()) {
            sb.append("Entities: none visible.\n");
        } else {
            long nearbyHostiles = entities.stream()
                    .filter(seen -> seen.entity() instanceof net.minecraft.world.entity.monster.Monster)
                    .filter(seen -> seen.distance() <= 12.0D)
                    .count();
            if (nearbyHostiles > 0) {
                sb.append("DANGER: ").append(nearbyHostiles)
                  .append(" hostile creature(s) are within 12 blocks.\n");
            }
            // A distance-only cap can hide the zombie attacking the bot behind twelve closer item
            // drops or farm animals. Threats and players are more important than ambient entities;
            // distance remains the ordering within each class.
            List<Perception.SeenEntity> orderedEntities = new ArrayList<>(entities);
            orderedEntities.sort(java.util.Comparator
                    .comparingInt(ObservationBuilder::entityPriority)
                    .thenComparingDouble(Perception.SeenEntity::distance));
            sb.append("Entities you can see:\n");
            for (int i = 0; i < Math.min(orderedEntities.size(), MAX_ENTITIES); i++) {
                Perception.SeenEntity seen = orderedEntities.get(i);
                BlockPos where = seen.entity().blockPosition();
                sb.append(String.format("  - %s at %s, %.1f blocks %s%n",
                        seen.description(), where.toShortString(), seen.distance(),
                        compass(bot, where)));
            }
            if (entities.size() > MAX_ENTITIES) {
                sb.append("  ... and ").append(entities.size() - MAX_ENTITIES).append(" more\n");
            }
        }

        List<Perception.SeenBlock> blocks = Perception.visibleBlocks(bot, radius);
        if (blocks.isEmpty()) {
            sb.append("Blocks: nothing notable visible.\n");
            return sb.toString();
        }

        // Aggregate by block type, but keep the nearest instance of each.
        //
        // The count alone is not actionable and that was a real defect: a bot looking at a forest
        // was told "Oak Log x14" and nothing else, so it had no way to walk to a tree and instead
        // wandered off announcing it could see no trees. A count answers "what is here"; only a
        // position answers "where do I go", which is the question the model actually has.
        Map<String, TypeSummary> summaries = new LinkedHashMap<>();
        for (Perception.SeenBlock seen : blocks) {
            String name = seen.state().getBlock().getName().getString();
            TypeSummary summary = summaries.get(name);
            if (summary == null) {
                summaries.put(name, new TypeSummary(seen.pos(), seen.distance(), 1));
            } else {
                summary.count++;
                if (seen.distance() < summary.nearestDistance) {
                    summary.nearest = seen.pos();
                    summary.nearestDistance = seen.distance();
                }
            }
        }

        List<Map.Entry<String, TypeSummary>> sorted = new ArrayList<>(summaries.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue().count, a.getValue().count));

        // Landmarks are listed first and are never pushed out by bulk terrain, however abundant it
        // is. Sorting purely by count meant a bot standing in a dark forest was shown the twelve
        // commonest plant types - all grass, ferns and flowers - with every oak log and leaf below
        // the cut. It then reported, accurately and uselessly, that its surroundings were all grass
        // and that there was not a single tree trunk anywhere. A single chest in a field of flowers
        // has the same problem: rarity is not unimportance.
        List<Map.Entry<String, TypeSummary>> landmarksFirst = new ArrayList<>();
        List<Map.Entry<String, TypeSummary>> ordinary = new ArrayList<>();
        for (Map.Entry<String, TypeSummary> entry : sorted) {
            boolean landmark = Perception.isLandmark(
                    bot.level().getBlockState(entry.getValue().nearest));
            (landmark ? landmarksFirst : ordinary).add(entry);
        }
        List<Map.Entry<String, TypeSummary>> ordered = new ArrayList<>(landmarksFirst);
        ordered.addAll(ordinary);

        sb.append("Blocks you can see within ").append(radius).append(" blocks (")
                .append(blocks.size()).append(" total, grouped; nearest instance of each). ")
                .append("A target marked OCCLUDED was noticed through the intentional light ")
                .append("see-through allowance; it is an interest target, not a directly walkable ")
                .append("coordinate. The mining tools will create a real access route first:\n");
        for (int i = 0; i < Math.min(ordered.size(), MAX_NAMED_BLOCKS); i++) {
            Map.Entry<String, TypeSummary> entry = ordered.get(i);
            TypeSummary summary = entry.getValue();
            int blockers = Perception.blockerCount(
                    bot, summary.nearest, Perception.SEE_THROUGH_BLOCKS + 1);
            sb.append("  - ").append(entry.getKey()).append(" x").append(summary.count)
              .append(" - nearest at ").append(summary.nearest.toShortString())
              .append(String.format(" (%.0f blocks %s)", summary.nearestDistance,
                      compass(bot, summary.nearest)));
            if (blockers > 0) {
                sb.append(" [OCCLUDED by ").append(blockers).append(" block")
                  .append(blockers == 1 ? "" : "s").append(']');
            } else {
                sb.append(" [EXPOSED]");
            }
            sb
              .append('\n');
        }
        if (ordered.size() > MAX_NAMED_BLOCKS) {
            sb.append("  ... and ").append(ordered.size() - MAX_NAMED_BLOCKS).append(" other kinds\n");
        }

        // A second, filtered pass out to twice the radius. The near scan above is dominated by
        // ordinary terrain, so it cannot answer "is there a forest over there" - the cap fills with
        // grass and stone long before it reaches anything worth walking to. This pass looks only for
        // silhouettes: trees, water, and anything placed or built.
        int farRadius = Math.min(radius * 2, (int) Perception.BLOCK_RANGE);
        java.util.Set<net.minecraft.world.level.block.Block> seenTypes = new java.util.HashSet<>();
        for (Perception.SeenBlock seen : blocks) {
            seenTypes.add(seen.state().getBlock());
        }
        List<Perception.SeenBlock> landmarks =
                Perception.visibleLandmarks(bot, radius, farRadius, seenTypes, 15L);

        // One line per kind, at its nearest position: a list of forty leaves is noise, "there is a
        // tree 26 blocks west" is a destination.
        Map<String, Perception.SeenBlock> fartherKinds = new LinkedHashMap<>();
        for (Perception.SeenBlock seen : landmarks) {
            whereverNearest(fartherKinds, seen);
        }
        if (!fartherKinds.isEmpty()) {
            sb.append("Further out, between ").append(radius).append(" and ").append(farRadius)
              .append(" blocks away (things worth walking to):\n");
            fartherKinds.values().stream()
                    .limit(MAX_NAMED_BLOCKS)
                    .forEach(seen -> sb.append("  - ")
                            .append(seen.state().getBlock().getName().getString())
                            .append(" at ").append(seen.pos().toShortString())
                            .append(String.format(" (%.0f blocks %s)", seen.distance(),
                                    compass(bot, seen.pos())))
                            .append('\n'));
        }

        // Containers are called out separately, because they are the main thing a bot acts on, and
        // because their contents are hidden until opened.
        List<BlockPos> containers = new ArrayList<>();
        for (Perception.SeenBlock seen : blocks) {
            if (Containers.isContainer(bot, seen.pos()) && containers.size() < 8) {
                containers.add(seen.pos());
            }
        }
        if (!containers.isEmpty()) {
            sb.append("Containers you can see:\n");
            for (BlockPos pos : containers) {
                boolean opened = Containers.hasOpened(bot, pos);
                sb.append("  - ").append(bot.level().getBlockState(pos).getBlock().getName().getString())
                  .append(" at ").append(pos.toShortString())
                  .append(opened ? " (OPENED - you may look inside)" : " (not opened - contents unknown)")
                  .append('\n');
            }
        }

        return sb.toString();
    }

    /** Keep only the nearest entry per block kind. */
    private static void whereverNearest(Map<String, Perception.SeenBlock> byKind, Perception.SeenBlock seen) {
        String name = seen.state().getBlock().getName().getString();
        Perception.SeenBlock existing = byKind.get(name);
        if (existing == null || seen.distance() < existing.distance()) {
            byKind.put(name, seen);
        }
    }

    /** Running tally for one block type in the near field. */
    private static final class TypeSummary {
        BlockPos nearest;
        double nearestDistance;
        int count;

        TypeSummary(BlockPos nearest, double nearestDistance, int count) {
            this.nearest = nearest;
            this.nearestDistance = nearestDistance;
            this.count = count;
        }
    }

    /** Name plus the durability a player can see on the HUD/inventory bar. */
    private static String describeEquippedStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return "nothing";
        }
        StringBuilder out = new StringBuilder()
                .append(stack.getCount()).append("x ").append(stack.getHoverName().getString());
        if (stack.isDamageableItem()) {
            out.append(" (durability ")
               .append(stack.getMaxDamage() - stack.getDamageValue())
               .append('/').append(stack.getMaxDamage()).append(')');
        }
        return out.toString();
    }

    /** Lower values survive the bounded entity list first. */
    private static int entityPriority(Perception.SeenEntity seen) {
        if (seen.entity() instanceof net.minecraft.world.entity.monster.Monster) {
            return 0;
        }
        if (seen.entity() instanceof Player) {
            return 1;
        }
        if (seen.entity() instanceof net.minecraft.world.entity.item.ItemEntity) {
            return 3;
        }
        return 2;
    }

    /**
     * A compass bearing from the bot to a position.
     *
     * <p>Absolute compass directions, not "left" or "right": the bot's facing changes constantly and
     * a relative direction is stale by the time it acts on it. Minecraft's axes are north = -Z and
     * east = +X.
     */
    public static String compass(ServerPlayer bot, BlockPos target) {
        double dx = target.getX() + 0.5D - bot.getX();
        double dz = target.getZ() + 0.5D - bot.getZ();
        if (Math.abs(dx) < 0.5D && Math.abs(dz) < 0.5D) {
            return "here";
        }
        // atan2 with north as -Z, measured clockwise.
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        String[] points = { "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west" };
        int index = (int) Math.round(((angle + 360.0D) % 360.0D) / 45.0D) % 8;
        return points[index];
    }

    /** The bot's carrying capacity and contents. */
    public static String describeInventory(ServerPlayer bot) {
        return describeInventory(bot, MAX_INVENTORY_LINES);
    }

    private static String describeInventory(ServerPlayer bot, int maxLines) {
        var inventory = bot.getInventory();
        List<String> items = new ArrayList<>();
        int occupied = 0;

        int carriedSlots = Math.min(36, inventory.getContainerSize());
        for (int slot = 0; slot < carriedSlots; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            occupied++;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String slotName = slot < 9 ? "hotbar " + slot : "slot " + slot;
            items.add(String.format("%sx %s [%s]", stack.getCount(), id, slotName));
        }

        if (items.isEmpty()) {
            return "0/36 carried slots occupied\n(empty)";
        }
        // Bound the listing: a bot with a full inventory would otherwise dump 36 lines every turn,
        // and a model does not need the last few slots spelled out to act sensibly.
        if (items.size() > maxLines) {
            int extra = items.size() - maxLines;
            items = new ArrayList<>(items.subList(0, maxLines));
            items.add("... and " + extra + " more stacks");
        }
        String pressure = occupied >= carriedSlots
                ? occupied + "/" + carriedSlots + " carried slots occupied - PACK FULL. Use "
                        + "discard for low-value clutter or deposit items before collecting more."
                : occupied + "/" + carriedSlots + " carried slots occupied";
        return pressure + "\n" + String.join("\n", items);
    }

    /**
     * The contents of a container the bot has opened, or an explanation of why they are unknown.
     *
     * <p>This is where the realism gate becomes visible to the model: an unopened container
     * reports that its contents are unknown rather than silently appearing empty.
     */
    public static String describeContainer(ServerPlayer bot, BlockPos pos) {
        if (!Containers.hasOpened(bot, pos)) {
            return "You have not opened this container, so you do not know what is inside. "
                    + "Use open_container first.";
        }

        List<Containers.SlotContents> contents = Containers.peek(bot, pos);
        if (contents.isEmpty()) {
            return "The container is empty.";
        }

        StringBuilder sb = new StringBuilder("Contents:\n");
        for (Containers.SlotContents slot : contents) {
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.stack().getItem()).toString();
            sb.append(String.format("  - slot %d: %dx %s%n", slot.slot(), slot.stack().getCount(), id));
        }
        return sb.toString();
    }

    /** A very short summary for use when the model needs a reminder without a full re-scan. */
    public static String brief(ServerPlayer bot) {
        Vec3 pos = bot.position();
        return String.format("%s at (%.0f, %.0f, %.0f) in %s, hp %.0f/%.0f",
                bot.getName().getString(),
                pos.x, pos.y, pos.z,
                bot.serverLevel().dimension().location().getPath(),
                bot.getHealth(), bot.getMaxHealth());
    }

    /** Who else is online — social information, not gated by sight. */
    public static String describeOnlinePlayers(ServerPlayer bot) {
        List<String> names = new ArrayList<>();
        for (ServerPlayer other : bot.server.getPlayerList().getPlayers()) {
            if (other != bot) {
                names.add(other.getName().getString());
            }
        }
        if (names.isEmpty()) {
            return "No other players are online.";
        }
        return "Other players online: " + String.join(", ", names);
    }

    /** Compose the standard system prompt for a bot. */
    public static String systemPrompt(ServerPlayer bot) {
        return """
                You are %s, a player on a Minecraft server. You are a real player in the world:
                you walk, mine, build, craft and talk, and other players see you as an ordinary person.

                You can only know what you can perceive. Your observation lists what is visible to you
                from where you stand, with the position of the nearest instance of each kind of block,
                so you can walk to anything you can see. Container contents are unknown until you
                open them. If something you want is not in the list, it is not necessarily absent from
                the world - it may simply be out of sight, so move and look again before concluding
                that there is none. Never say a thing does not exist nearby just because your last
                look did not show it.

                Your observation also includes chat you can hear. Lines marked NEW have arrived since
                you last acted. Every NEW line caused this decision immediately, but it may be meant
                for somebody else and does not force a reply. Decide from the conversation. If a
                player expects an answer from you, reply with the say tool in their language; if not,
                stay silent. Keep any reply short, like a person chatting in a game.

                Your own language is 简体中文: speak Chinese by default, including when you start a
                conversation yourself, and answer in the language a player used only when that is not
                Chinese. If a player asks you to switch language - "转中文", "说中文", "speak
                Chinese" - obey it immediately, in that language, and remember it for the rest of the
                session. Never answer a Chinese message in English.

                Act by calling the tools provided. LLM calls are slow, so never spend one call on one
                tiny action when you already know the next actions. For any task with two or more
                known steps, PREFER the plan tool: put 4-12 useful actions in one strictly sequential
                plan when the observation gives you enough information, up to 24 when the work is
                repetitive. "Walk to the tree, chop it, return, deposit the logs" is one plan, not four
                decisions. The server executes that plan while your next LLM call is still running.
                A plan that drains leaves you idle, and an idle bot is asked to plan again - so a
                one-step plan costs one full call per action. Batch the whole errand.
                A plan stops at the first failed step by default, so stale dependent steps do not make
                things worse; use continue_on_failure only for a genuinely independent step.

                When the observation shows actions already WAITING TO RUN, extend that queue with new
                work instead of repeating its steps. Do not pad plans with ceremonial observe,
                look_at, hold or stop calls: movement, equipping and reach are handled by the action
                tools. Use a single direct tool call only for a reactive answer, an interrupt, or a
                decision whose next step genuinely depends on seeing this step's result.

                Multiple direct tool calls are also accepted and run in the order you list them, but
                plan is more reliable for long work because it packages the whole sequence in one
                tool call. "Walk to the tree, then chop it" can be:
                  plan([{goto...}, {mine...}])
                and the chopping starts the moment you arrive, with no LLM wait in between.
                Talking and looking around happen immediately even while you are walking, so put them
                wherever you want them to happen:
                  goto(x, z) and say("on my way")
                  goto(x, z) and eat()
                Actions that need you to be somewhere wait until you get there; actions that only need
                your voice or your eyes do not. If an action fails, the reason is reported; do not
                repeat a failed step unchanged.

                Aim at a spot you can actually see in your block list rather than guessing coordinates.
                A goto that reports "no route" means something solid is in the way: look around and aim
                somewhere closer that you can walk to, instead of asking for the same place again.

                Mining underground must leave you a way back out. ALWAYS use dig_tunnel for repeated
                underground excavation: mode=down makes a descending staircase and mode=level makes a
                branch tunnel. It remembers the one established entrance allowed near home and its
                working face; after unloading at storage, call it normally and it returns through that
                entrance before continuing. You cannot replace that entrance or open another surface
                hole. Never construct a staircase by guessing individual mine coordinates: those calls
                are refused near the home surface because they produce pits and trenches.
                Repeat dig_tunnel in chunks of up to 24 blocks. If you end up below ground with
                no walking route, use escape_up once: the server will dig and climb a real staircase
                with ordinary mining time. If it cannot find a safe staircase, use return_to_spawn;
                that emergency action teleports you to your bed/anchor without killing you or dropping
                your inventory. Do not keep retrying goto, /home, /spawn or guessed place calls while
                your coordinates remain unchanged.

                To fell a tree or clear a vein, prefer mine_resource(resource, vein_radius=6): it
                searches perceived blocks instead of making you copy or guess a coordinate, and it
                creates a real access tunnel when light-x-ray perception noticed the target through
                terrain. Use mine(x, y, z, radius=6) when an exact target coordinate is already known.
                Both jobs keep breaking connected blocks of the same kind. What each block drops goes
                straight into your pack as it falls, so you never have to walk over anything you mined.

                Conserve iron tools: ordinary stone, cobblestone, deepslate and tunnel clearance use
                a stone pickaxe whenever one is carried. Iron pickaxes are reserved for diamond and
                other ores. The server selects this automatically even if you name a wasteful tool.

                You may be asked what to do while a long action is still running. Your observation then
                says WHAT YOU ARE DOING RIGHT NOW. If you are part-way through something and already
                know the replacement steps, submit one plan with replace_current=true; it atomically
                stops the old action, discards its stale queue, and starts the replacement. Use a
                direct interrupt only when you must stop now but do not yet know the replacement.
                If the action is still what you want, do not interrupt or replace it.

                Anything else left on the ground - what you drop, what someone gives you - is reported
                to you; use pickup, or walk over it.

                Stay alive the way a careful survival player would. Health, hunger, air, armor,
                daylight, weather, light and nearby hostiles are part of your observation. Immediate
                danger outranks an ordinary task: eat before starving, get out of fire or deep water,
                avoid dark unsafe routes, and do not pick a fight you are unequipped to survive. The
                attack tool is a sustained fight, not one swing; it pursues a visible target and keeps
                striking at normal cooldown speed until the target dies or gets away. Use interrupt to
                disengage. Sleeping sets your respawn point and skips a dangerous night when possible.

                Keep notes with the remember tool. You forget everything between sessions except those
                notes, so use them for anything worth knowing tomorrow: what a machine or block is for,
                which chest holds what, where your home is, what a player asked you to do.

                Every player message you can hear immediately triggers a fresh decision with your
                complete current state. Evaluate it, but do not assume every message needs an answer:
                other players may simply be talking to each other. Silence is valid. If you do reply,
                use the say tool; ordinary prose in your model response is private thought.

                When you speak to players, use the say tool. Keep messages short and natural - you are
                chatting in a game, not writing an essay. Do not announce what you are about to do and
                do not narrate your progress: nobody wants a running commentary of your plans, and most
                turns should contain no chat at all. Speak when a player has spoken to you and expects
                an answer, when you have finished something a player asked for, when you need to ask a
                question or report a problem, or when you have something a person would actually find
                worth hearing. Never send two messages in a row that say the same thing in different
                words. Be a good server-mate: do not destroy other people's builds or take their things.
                """.formatted(bot.getName().getString());
    }

    /** Players other than the bot that are currently visible to it. */
    public static List<Player> visiblePlayers(ServerPlayer bot) {
        List<Player> out = new ArrayList<>();
        for (Perception.SeenEntity seen : Perception.visibleEntities(bot)) {
            if (seen.entity() instanceof Player player && player != bot) {
                out.add(player);
            }
        }
        return out;
    }
}
