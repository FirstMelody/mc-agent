package com.melody.mcagent.rt.perception;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deterministic "a player built this" layer, and the hard rule that follows from it.
 *
 * <p>The bot used to treat a player's base as ordinary terrain: it opened mine shafts through the
 * camp floor and dug a staircase out through the house wall, because {@code dig_tunnel} and
 * {@code escape_up} only knew about fluids, falling blocks, block entities and unbreakable terrain.
 * A model told in prose not to damage the base still cannot see the base; the geometric features had
 * never been measured. So they are measured here and the break is refused in the action layer.
 *
 * <p>Two independent signals, cheapest first:
 * <ol>
 *   <li><b>Fixtures</b> — beds, storage, workstations, doors, glass, torches, and anything carrying
 *       a block entity (machines, signs, banners). A fixture is never natural terrain, so breaking
 *       one is refused with no scan at all.</li>
 *   <li><b>Structures</b> — a bounded scan looks for building-palette blocks (planks, stairs,
 *       slabs, bricks, wool, terracotta, concrete, metal blocks, glass) clustered around at least
 *       one fixture. When one is found the whole box is protected, including {@link #SUBSTRATE}
 *       blocks below it: a hole in the floor is the same damage as a hole in the wall, and digging
 *       up from underneath undermines the building just as well.</li>
 * </ol>
 *
 * <p>This is deliberately geometric and conservative. It also protects a village house, a
 * mineshaft's plank lining or a dungeon chest, which costs the bot nothing it actually needs.
 * Building blocks are recognised by block-id words rather than a fixed list, so modded building and
 * machine blocks are covered too.
 *
 * <p>An operator who genuinely wants a demolition bot can start the server with
 * {@code MCAGENT_STRUCTURE_GUARD=off}. The switch is environment-gated, so a production server
 * cannot turn it off by accident, and the dev harness uses it to prove that this guard — and not
 * something else — is what stops the bot.
 */
public final class PlayerStructure {

    private static final Logger LOG = LoggerFactory.getLogger("mcagent/structure");

    /** Block ids the classifier could not handle; logged once each, and bounded. */
    private static final Set<String> CLASSIFY_WARNINGS = java.util.concurrent.ConcurrentHashMap
            .newKeySet();

    /** Horizontal scan radius, in blocks, around the reference position. */
    public static final int RADIUS = 10;
    /** How far below and above the reference position the scan reaches. */
    private static final int BELOW = 5;
    private static final int ABOVE = 7;
    /** Blocks below a structure that stay protected, so its floor and foundation cannot be cut. */
    public static final int SUBSTRATE = 6;
    /** Minimum building-palette blocks before a cluster counts as a structure. */
    private static final int MIN_CONSTRUCTED = 16;
    /** Fixtures reported in text; the rest only widen the region. */
    private static final int MAX_FIXTURE_NAMES = 6;

    private static final int CONSTRUCTED = 1;
    private static final int FIXTURE = 2;

    private static final Map<Block, Integer> CLASS_CACHE = new HashMap<>();
    private static final Map<Block, String> NAME_CACHE = new HashMap<>();
    private static final Map<Block, Set<String>> TOKEN_CACHE = new HashMap<>();

    /** Memoized detection, keyed by an 8-block cell and valid for {@link #CACHE_TICKS}. */
    private static final Map<Long, Memo> MEMO = new HashMap<>();
    private static final int CACHE_TICKS = 100;
    private static final int CACHE_LIMIT = 512;

    private PlayerStructure() {
    }

    /**
     * Whether the protection rule is in force.
     *
     * <p>Environment-gated rather than config-gated on purpose: the dev harness disables it to prove
     * that the guard, and nothing else, is what stops the bot. No production server sets this.
     */
    public static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getenv("MCAGENT_STRUCTURE_GUARD"));
    }

    /** A protected box: the scanned structure plus one block of margin and its foundation. */
    public record Region(BlockPos min, BlockPos max, int constructed, List<String> fixtures) {

        public boolean contains(BlockPos pos) {
            return pos.getX() >= this.min.getX() && pos.getX() <= this.max.getX()
                    && pos.getY() >= this.min.getY() && pos.getY() <= this.max.getY()
                    && pos.getZ() >= this.min.getZ() && pos.getZ() <= this.max.getZ();
        }

        public BlockPos center() {
            return new BlockPos((this.min.getX() + this.max.getX()) / 2,
                    (this.min.getY() + this.max.getY()) / 2,
                    (this.min.getZ() + this.max.getZ()) / 2);
        }

        /** Short, LLM-readable description: what it is, where it is, and what is in it. */
        public String describe() {
            StringBuilder out = new StringBuilder("player-built structure around ")
                    .append(center().toShortString())
                    .append(" (protected box ").append(this.min.toShortString()).append("..")
                    .append(this.max.toShortString()).append(", ")
                    .append(this.constructed).append(" built block(s)");
            if (!this.fixtures.isEmpty()) {
                out.append("; fixtures: ").append(String.join(", ", this.fixtures));
            }
            return out.append(')').toString();
        }
    }

    private record Memo(@Nullable Region region, long validUntil) {
    }

    /** Building-palette words, matched against the underscore-separated tokens of a block id. */
    private static final Set<String> CONSTRUCTED_TOKENS = Set.of(
            "planks", "brick", "bricks", "glass", "pane", "wool", "concrete",
            "stairs", "slab", "fence", "wall", "door", "trapdoor", "button", "plate", "sign",
            "banner", "candle", "lantern", "torch", "bed", "ladder", "scaffolding", "tiles",
            "pillar", "grate", "quartz", "prismarine", "chain", "bookshelf", "table", "loom",
            "chest", "barrel", "shulker", "furnace", "smoker", "anvil", "cauldron", "beacon",
            "jukebox", "hopper", "dispenser", "dropper", "piston", "observer", "repeater",
            "comparator", "lever", "target", "rail", "conduit", "lamp", "casing", "bars",
            "sheetmetal", "locometal", "plating", "cobblestone");

    /** Multi-word building fragments, matched with {@code contains}. */
    private static final String[] CONSTRUCTED_PHRASES = {
            "crafting_table", "smooth_stone", "polished_", "chiseled_", "cut_", "stone_brick",
            "iron_block", "gold_block", "copper_block", "diamond_block", "emerald_block",
            "netherite_block", "lapis_block", "coal_block", "redstone_block", "bamboo_block",
            "daylight_detector", "note_block", "redstone_lamp", "sea_lantern", "smithing",
            "cartography", "stonecutter", "grindstone", "composter", "lectern",
            "brewing_stand", "enchanting", "respawn_anchor", "lodestone", "flower_pot",
            "glazed_terracotta", "cobbled_deepslate", "sheet_metal", "hempcrete", "blastbrick",
            "coke_brick", "treated_wood", "engineering", "steel_post",
    };

    /**
     * Ids that are unambiguously world generation and must never read as player work, whatever
     * words they contain. Two of these are here because a building word appears inside a natural
     * name ("rose_quartz" contains "quartz", "endbloom" contains "loom"); the rest are natural
     * block entities and buried loot containers.
     */
    private static final String[] NATURAL_PHRASES = {
            "rose_quartz", "suspicious_sand", "suspicious_gravel", "glowing_moss_carpet",
            "endbloom", "decorated_pot",
    };

    /** Fixture words: furniture, storage, workstations and machinery. */
    private static final Set<String> FIXTURE_TOKENS = Set.of(
            "chest", "barrel", "shulker", "furnace", "smoker", "anvil", "cauldron", "brewing",
            "enchanting", "lectern", "loom", "stonecutter", "smithing", "grindstone",
            "cartography", "composter", "beacon", "jukebox", "bookshelf", "bed", "sign", "banner",
            "lantern", "torch", "ladder", "scaffolding", "glass", "pane", "bell",
            "hopper", "dispenser", "dropper", "piston", "observer", "repeater", "comparator",
            "lever", "target", "rail", "conduit", "lodestone", "campfire", "candle", "table",
            "shelf", "cabinet", "drawer", "crate", "backpack", "machine", "generator", "tank",
            "pipe", "cable", "lamp", "chair", "window", "door", "trapdoor", "gate", "workbench",
            "anchor", "detector", "tripwire", "note");

    /**
     * Block entities that world generation places on its own.
     *
     * <p>Matched as whole ids, not substrings: a {@code create:item_vault} is a player's storage
     * machine and must not be exempted by vanilla's {@code vault}. Craftable beehives are
     * deliberately absent for the same reason - only the natural {@code bee_nest} is exempt.
     */
    private static final Set<String> NATURAL_BLOCK_ENTITIES = Set.of(
            "spawner", "trial_spawner", "vault", "bee_nest", "sculk_sensor",
            "calibrated_sculk_sensor", "sculk_shrieker", "sculk_catalyst", "end_gateway",
            "end_portal", "chorus_flower");

    /**
     * Classify a block as building material and/or a fixture. Matched by block-id words so modded
     * building blocks are covered as well as vanilla ones.
     */
    private static int classify(BlockState state) {
        Block block = state.getBlock();
        Integer cached = CLASS_CACHE.get(block);
        if (cached != null) {
            return cached;
        }
        int flags;
        try {
            flags = classifyUncached(state);
        } catch (Throwable t) {
            // A classifier that blows up must not silently switch the guard off, and it must not
            // throw out of the action layer either. Decorative id shapes are what actually trip
            // this (mods ship ids like "chipped:bricks_bricks"), so the block is treated as
            // player-building material and cached that way.
            flags = CONSTRUCTED;
            if (CLASSIFY_WARNINGS.add(name(state)) && CLASSIFY_WARNINGS.size() <= 8) {
                LOG.warn("Could not classify block {}; treating it as player-built: {}",
                        name(state), t.toString());
            }
        }
        CLASS_CACHE.put(block, flags);
        return flags;
    }

    private static int classifyUncached(BlockState state) {
        String path = name(state);
        // Ore and raw storage are nature. A modded ore with a block entity must not read as a
        // player-placed machine.
        if (path.contains("_ore") || path.startsWith("raw_")) {
            return 0;
        }
        for (String natural : NATURAL_PHRASES) {
            if (path.contains(natural)) {
                return 0;
            }
        }
        int flags = 0;
        if (matchesPalette(path, state)) {
            flags |= CONSTRUCTED;
        }
        if (isFixture(path, state)) {
            flags |= FIXTURE;
        }
        return flags;
    }

    private static boolean matchesPalette(String path, BlockState state) {
        // BlockTags.TERRACOTTA is deliberately absent: badlands world generation lays down plain,
        // white, orange and yellow terracotta in bulk, which would flood the count and let one
        // stray torch protect a whole mesa. Glazed terracotta is crafted, and covered by phrase.
        if (state.is(BlockTags.PLANKS) || state.is(BlockTags.WOODEN_STAIRS)
                || state.is(BlockTags.WOODEN_SLABS) || state.is(BlockTags.WOODEN_FENCES)
                || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.WOODEN_DOORS)
                || state.is(BlockTags.WOODEN_TRAPDOORS) || state.is(BlockTags.WOOL)
                || state.is(BlockTags.WOOL_CARPETS)
                || state.is(BlockTags.STONE_BRICKS) || state.is(BlockTags.IMPERMEABLE)
                || state.is(BlockTags.BEDS) || state.is(BlockTags.SHULKER_BOXES)
                || state.is(BlockTags.BEACON_BASE_BLOCKS) || state.is(BlockTags.BANNERS)
                || state.is(BlockTags.ALL_SIGNS) || state.is(BlockTags.FLOWER_POTS)
                || state.is(BlockTags.CANDLES) || state.is(BlockTags.CAULDRONS)
                || state.is(BlockTags.ANVIL) || state.is(BlockTags.CAMPFIRES)
                || state.is(BlockTags.RAILS)) {
            return true;
        }
        for (String token : tokens(state)) {
            if (CONSTRUCTED_TOKENS.contains(token)) {
                return true;
            }
        }
        for (String phrase : CONSTRUCTED_PHRASES) {
            if (path.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFixture(String path, BlockState state) {
        // Natural block entities first: a decorated pot must not be read as a player's flower pot
        // just because its id contains "pot".
        if (NATURAL_BLOCK_ENTITIES.contains(path)) {
            return false;
        }
        if (state.is(BlockTags.BEDS) || state.is(BlockTags.SHULKER_BOXES) || state.is(BlockTags.BANNERS)
                || state.is(BlockTags.ALL_SIGNS) || state.is(BlockTags.CANDLES)
                || state.is(BlockTags.FLOWER_POTS) || state.is(BlockTags.CAULDRONS)
                || state.is(BlockTags.ANVIL) || state.is(BlockTags.CAMPFIRES)
                || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)
                || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.RAILS)) {
            return true;
        }
        for (String token : tokens(state)) {
            if (FIXTURE_TOKENS.contains(token)) {
                return true;
            }
        }
        // Anything else carrying a block entity is a machine, container or decoration.
        return state.hasBlockEntity();
    }

    /**
     * The underscore-separated words of a block id.
     *
     * <p>Duplicate words are ordinary (mods ship {@code chipped:bricks_bricks}), and {@code Set.of}
     * rejects duplicates by throwing, which used to escape the classifier entirely.
     */
    private static Set<String> tokens(BlockState state) {
        return TOKEN_CACHE.computeIfAbsent(state.getBlock(),
                b -> Set.copyOf(new java.util.LinkedHashSet<>(
                        List.of(name(b).split("_")))));
    }

    private static String name(BlockState state) {
        return name(state.getBlock());
    }

    private static String name(Block block) {
        return NAME_CACHE.computeIfAbsent(block,
                b -> BuiltInRegistries.BLOCK.getKey(b).getPath().toLowerCase(Locale.ROOT));
    }

    /**
     * Scan around a position for a player-built structure, or {@code null} when the neighbourhood
     * is ordinary terrain.
     */
    @Nullable
    public static Region detect(ServerLevel level, BlockPos around) {
        if (!enabled()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int constructed = 0;
        LinkedHashSet<String> fixtures = new LinkedHashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int y = around.getY() - BELOW; y <= around.getY() + ABOVE; y++) {
            if (level.isOutsideBuildHeight(y)) {
                continue;
            }
            for (int x = around.getX() - RADIUS; x <= around.getX() + RADIUS; x++) {
                for (int z = around.getZ() - RADIUS; z <= around.getZ() + RADIUS; z++) {
                    BlockPos pos = cursor.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || !state.getFluidState().isEmpty()) {
                        continue;
                    }
                    int flags = classify(state);
                    if (flags == 0) {
                        continue;
                    }
                    if ((flags & FIXTURE) != 0 && fixtures.size() < MAX_FIXTURE_NAMES) {
                        fixtures.add(name(state) + "@" + pos.toShortString());
                    }
                    constructed++;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    maxZ = Math.max(maxZ, z);
                }
            }
        }
        if (constructed < MIN_CONSTRUCTED || fixtures.isEmpty()) {
            return null;
        }
        // One block of margin, two above, and a real foundation underneath.
        return new Region(
                new BlockPos(minX - 1, minY - SUBSTRATE, minZ - 1),
                new BlockPos(maxX + 1, maxY + 2, maxZ + 1),
                constructed, List.copyOf(fixtures));
    }

    /** {@link #detect} memoized per 8-block cell, because mining plans query hundreds of cells. */
    @Nullable
    public static Region detectCached(ServerLevel level, BlockPos around) {
        if (!enabled()) {
            return null;
        }
        long key = cellKey(around);
        long now = level.getGameTime();
        synchronized (MEMO) {
            Memo memo = MEMO.get(key);
            if (memo != null && memo.validUntil() >= now) {
                return memo.region();
            }
        }
        Region region = detect(level, around);
        synchronized (MEMO) {
            if (MEMO.size() > CACHE_LIMIT) {
                MEMO.clear();
            }
            MEMO.put(key, new Memo(region, now + CACHE_TICKS));
        }
        return region;
    }

    /**
     * Why this block must not be broken, or {@code null} when it is ordinary terrain.
     *
     * @param around the position the question is asked from (usually the bot); the structure scan
     *               runs around it and falls back to a scan around the target itself
     * @param target the block about to be broken
     */
    @Nullable
    public static String protectionReason(ServerLevel level, BlockPos around, BlockPos target) {
        if (!enabled()) {
            return null;
        }
        BlockState state = level.getBlockState(target);
        if (state.isAir()) {
            return null;
        }
        // Crops and farmland are not a structure. A field is renewable produce - taking the wheat is
        // what a field is for - and without this the bot can be locked out of its own farm by its own
        // lighting: a torch counts as both building material and a fixture, so a field near any build
        // (or one with enough torches in it) would otherwise have every ripe crop refused as part of
        // "a player's structure". Everything else in the region stays protected, torches included.
        if (Crops.isCrop(state) || Crops.isFarmland(state)) {
            return null;
        }
        if ((classify(state) & FIXTURE) != 0) {
            return name(state) + " at " + target.toShortString()
                    + " is something a player placed (furniture, storage or a machine)";
        }
        Region near = detectCached(level, around);
        if (near != null && near.contains(target)) {
            return target.toShortString() + " is inside the " + near.describe();
        }
        if (!around.equals(target)) {
            Region atTarget = detectCached(level, target);
            if (atTarget != null && atTarget.contains(target)) {
                return target.toShortString() + " is inside the " + atTarget.describe();
            }
        }
        return null;
    }

    /** Convenience form of {@link #protectionReason} for planners and clearance checks. */
    public static boolean isProtected(ServerLevel level, BlockPos around, BlockPos target) {
        return protectionReason(level, around, target) != null;
    }

    /**
     * Whether a single block is a fixture, without any scan. Used by planners that must stay O(1)
     * per node and already know the structure box for their own neighbourhood.
     */
    public static boolean isFixture(BlockState state) {
        return !state.isAir() && (classify(state) & FIXTURE) != 0;
    }

    /** The observation line: what the bot is standing in, and what that means for mining. */
    public static String describe(ServerLevel level, BlockPos around) {
        if (!enabled()) {
            return "  - player-built structures: the operator turned this protection off\n";
        }
        Region region = detectCached(level, around);
        if (region == null) {
            return "  - player-built structure: none within " + RADIUS
                    + " blocks; everything around you is natural terrain, so mining here is allowed\n";
        }
        return "  - player-built structure: " + region.describe() + "\n"
                + "    This is a player's building, not terrain. Breaking any block inside it, or in the "
                + SUBSTRATE + " blocks under it, is refused: no tunnels, no holes in the floor, no "
                + "mining through its walls or roof. Leave through its own door or opening, and mine "
                + "natural ground away from it.\n";
    }

    private static long cellKey(BlockPos pos) {
        return BlockPos.asLong(pos.getX() >> 3, pos.getY() >> 3, pos.getZ() >> 3);
    }
}
