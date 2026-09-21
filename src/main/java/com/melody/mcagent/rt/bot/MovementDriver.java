package com.melody.mcagent.rt.bot;

import java.util.ArrayDeque;

import com.melody.mcagent.rt.path.PathFinder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Drives a {@link Player}'s movement inputs so that vanilla physics walks it to a target.
 *
 * <p>Vanilla players never move themselves: a client sets {@code zza}/{@code xxa} and the server
 * integrates {@code LivingEntity.travel()} with real collision. We do exactly what a client does —
 * set the public input fields and the yaw — and let the engine do the rest. That means the bot
 * obeys the same collision, gravity, slab-stepping and fluid rules as a real player.
 *
 * <p>The yaw is derived from {@code Entity.getInputVector}, which rotates the input vector by
 * {@code getYRot()} as {@code (x*cos - z*sin, z*cos + x*sin)}. For full forward input
 * ({@code zza = 1}) that yields the world direction {@code (-sin(yaw), cos(yaw))}, so the yaw that
 * points at a horizontal offset {@code (dx, dz)} is {@code atan2(-dx, dz)}.
 *
 * <p>Inputs are written <em>after</em> the physics tick, because {@code LivingEntity.aiStep}
 * decays them by {@code 0.98} each tick and consumes them for the tick it runs in. Writing them
 * afterwards means they are read at full strength on the next tick.
 */
public final class MovementDriver {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("mcagent/movement");

    /** Horizontal distance at which the target counts as reached. */
    private static final double ARRIVE_RADIUS = 0.6D;
    /**
     * Vertical tolerance for "reached". Targets are often specified as a block coordinate that the
     * bot's feet cannot occupy exactly (it stands on top of the block, and a requested Y may be the
     * block's own coordinate). Being slightly above or below the requested Y is normal and must not
     * prevent arrival — otherwise the bot paces back and forth at a target it has effectively
     * reached.
     */
    private static final double ARRIVE_VERTICAL_TOLERANCE = 2.5D;
    /**
     * A path waypoint is an exact feet position, unlike a user-supplied block target. In
     * particular, a descending stair must not complete while the bot is still standing on the
     * tread above it: the next queued action may rely on the new floor actually being beneath the
     * player.
     */
    private static final double PATH_WAYPOINT_VERTICAL_TOLERANCE = 0.5D;
    /** If only the vertical offset remains and we have been close for this long, accept arrival. */
    private static final int CLOSE_ENOUGH_TICKS = 20;
    /** Only sprint when there is a meaningful distance left to cover. */
    private static final double SPRINT_MIN_DISTANCE = 4.0D;
    /** Movement below this per tick counts as "not making progress". */
    private static final double STUCK_EPSILON = 0.002D;
    /** Ticks of no progress before we try jumping (a one-block ledge or similar). */
    private static final int STUCK_TICKS_BEFORE_JUMP = 4;
    /** Ticks of no progress before we suspect a shut door and open it. */
    private static final int STUCK_TICKS_BEFORE_DOOR = 2;
    /** Give up after this many stalled ticks; a real path is needed (or we are walled in). */
    private static final int MAX_STUCK_TICKS = 120;
    /**
     * Ticks without getting meaningfully closer to the target before the driver gives up.
     *
     * <p>{@link #MAX_STUCK_TICKS} only counts ticks where the bot did not move <em>at all</em>, and
     * that is not the same thing as making progress. A bot standing in flowing water is nudged a
     * fraction of a block every tick - measured in play at 0.0004 blocks - which is enough to keep
     * resetting the "did not move" counter while going nowhere. The bot then held its target
     * forever, and because the brain declines to think while a walk is in progress, it stopped
     * responding entirely: ten minutes of total silence, standing in the same spot.
     *
     * <p>Progress toward the goal is the thing that actually matters, so it is measured directly.
     */
    private static final int NO_PROGRESS_TICKS = 100;
    /** How far the drowning reflex looks for dry land, in blocks. */
    private static final int SHORE_SEARCH_RADIUS = 8;
    /** Re-scan for shore this often while escaping, instead of every tick. */
    private static final int SHORE_RESCAN_TICKS = 10;

    /**
     * The player being steered. Not final: respawning replaces the entity, and a driver left
     * pointing at the discarded body would steer a corpse — the bot would stand motionless at its
     * spawn point while the brain believed it was walking.
     */
    private Player bot;
    @Nullable
    private Vec3 target;

    private double lastX;
    private double lastZ;
    private int stuckTicks;
    private int closeTicks;
    private boolean arrived = true;
    /** Remaining waypoints of an A* path, or null when steering straight at {@link #target}. */
    @Nullable
    private ArrayDeque<Vec3> waypoints;
    /** True while the drowning reflex is overriding the brain's requested movement. */
    private boolean escapingWater;
    /** Cached dry-land target used while escaping water. */
    @Nullable
    private Vec3 shoreTarget;
    /** Ticks until the shore search is re-run. */
    private int shoreRescanIn;
    /** Doors this driver has opened, for diagnostics. */
    private int openedDoors;
    /** Closest this driver has ever got to the current target. */
    private double bestDistanceToTarget = Double.MAX_VALUE;
    /** Ticks spent without getting meaningfully closer to the current target. */
    private int noProgressTicks;

    public MovementDriver(Player bot) {
        this.bot = bot;
        this.lastX = bot.getX();
        this.lastZ = bot.getZ();
    }

    /** Re-point the driver at the player's new body after a respawn, and forget the old journey. */
    public void rebind(Player newBot) {
        this.bot = newBot;
        this.clear();
        this.lastX = newBot.getX();
        this.lastZ = newBot.getZ();
    }

    /** Begin walking to a world position. */
    public void setTarget(Vec3 target) {
        this.target = target;
        this.stuckTicks = 0;
        this.closeTicks = 0;
        this.arrived = false;
        this.lastX = this.bot.getX();
        this.lastZ = this.bot.getZ();
        this.bestDistanceToTarget = Double.MAX_VALUE;
        this.noProgressTicks = 0;
    }

    /** Stop and forget the target. */
    public void clear() {
        this.target = null;
        this.waypoints = null;
        this.arrived = true;
        this.stuckTicks = 0;
        this.closeTicks = 0;
        this.bestDistanceToTarget = Double.MAX_VALUE;
        this.noProgressTicks = 0;
        this.stopInputs();
    }

    /** What came of a pathing request. */
    public enum Plan {
        /** A route was found and the bot is following it. */
        FOUND,
        /** The goal is further away than the caller allowed us to plan for. */
        TOO_FAR,
        /** No route exists: walled in, or the goal itself is unreachable. */
        BLOCKED
    }

    /**
     * Path to a target using A*, then follow the resulting waypoints.
     *
     * <p>An earlier version fell back to straight-line steering whenever A* failed, on the theory
     * that the bot should always have an intention. That was worse than useless: "no route" and "the
     * route is a straight line" are completely different situations, and the fallback turned the
     * first into the second. In production it produced a bot that stood inside a house with an open
     * door in front of it, reported "no path" on a target six blocks away, and then walked into the
     * wall — over and over, because each turn re-derived the same doomed instruction. Reporting the
     * failure lets the caller change the request instead of repeating it.
     */
    public Plan setPathTarget(BlockPos goal, int maxRange) {
        if (!(this.bot.level() instanceof ServerLevel level)) {
            return Plan.BLOCKED;
        }
        BlockPos start = this.bot.blockPosition();

        // Range first. A* would otherwise expand its entire node budget and then fail, spending a
        // full search to learn something a single subtraction answers.
        if (start.distSqr(goal) > (double) maxRange * maxRange) {
            return Plan.TOO_FAR;
        }

        PathFinder.Path path = PathFinder.findPath(level, start, goal, maxRange);
        if (path == null) {
            this.waypoints = null;
            // Drop any stale target as well: leaving the old one in place would have the bot finish
            // walking somewhere it no longer has any reason to go.
            this.clear();
            return Plan.BLOCKED;
        }
        if (path.isEmpty()) {
            // Already standing on the goal.
            this.waypoints = null;
            this.clear();
            return Plan.FOUND;
        }

        this.waypoints = new ArrayDeque<>(PathFinder.toVec3(path.waypoints()));
        this.setTarget(this.waypoints.poll());
        return Plan.FOUND;
    }

    /** Advance to the next waypoint on the current path, if any. */
    private void advanceWaypoint() {
        if (this.waypoints != null && !this.waypoints.isEmpty()) {
            this.setTarget(this.waypoints.poll());
        }
    }

    public boolean hasTarget() {
        return this.target != null;
    }

    /** True once the last requested target has been reached. */
    public boolean hasArrived() {
        return this.arrived;
    }

    /** True if we gave up because no progress was possible. */
    public boolean isStuck() {
        return this.stuckTicks > MAX_STUCK_TICKS;
    }

    @Nullable
    public Vec3 getTarget() {
        return this.target;
    }

    private void stopInputs() {
        this.bot.zza = 0.0F;
        this.bot.xxa = 0.0F;
        this.bot.setSprinting(false);
        this.bot.setJumping(false);
    }

    /**
     * Compute and write this tick's inputs. Must be called once per server tick, after the bot's
     * physics has run for that tick.
     */
    public void tick() {
        Player bot = this.bot;

        // The drowning reflex runs first and unconditionally, because it has to work when the bot
        // has no target at all - standing still in deep water is exactly how it dies.
        boolean escaping = this.escapeWater(bot);
        if (this.escapingWater && !escaping) {
            // Just left the water: cancel the upward thrust the reflex was holding, or the bot
            // bunny-hops across the bank.
            bot.setJumping(false);
        }
        this.escapingWater = escaping;
        if (escaping) {
            // The reflex owns the inputs, but the target still has to be able to time out: a bot
            // treading water that can never reach the shore would otherwise keep claiming to be on
            // its way for ever, and the brain would wait for a journey that never ends.
            if (this.target != null && ++this.noProgressTicks > NO_PROGRESS_TICKS * 3) {
                LOG.info("Gave up on a target that could not be reached while escaping water");
                this.arrived = false;
                this.target = null;
                this.waypoints = null;
                this.noProgressTicks = 0;
                this.bestDistanceToTarget = Double.MAX_VALUE;
            }
            return;
        }

        // States in which LivingEntity.aiStep zeroes the inputs anyway (isImmobile), or where a
        // different controller owns movement. Do not fight the engine.
        if (this.target == null
                || bot.isRemoved()
                || bot.isDeadOrDying()
                || bot.isSleeping()
                || bot.isPassenger()
                || bot.getAbilities().flying) {
            this.stopInputs();
            return;
        }

        double dx = this.target.x - bot.getX();
        double dz = this.target.z - bot.getZ();
        double dy = this.target.y - bot.getY();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        // Arrival: inside the radius. Vertically we accept a generous band, because a caller
        // naturally names the block coordinate while the bot stands on top of it, and because a
        // target may be at the bottom of a drop the bot has already descended.
        boolean horizontallyClose = horizontal <= ARRIVE_RADIUS;
        // Being above a named block is normal: callers often give the block's own Y. Being one
        // block BELOW an actual path waypoint is not arrival — it means the bot is pressed against
        // the next stair and still has to jump. The old symmetric tolerance silently completed
        // upward waypoints from the lower tread.
        boolean followingPath = this.waypoints != null;
        double belowTolerance = followingPath
                ? PATH_WAYPOINT_VERTICAL_TOLERANCE
                : ARRIVE_VERTICAL_TOLERANCE;
        boolean reached = horizontallyClose
                && dy < 0.5D
                && dy > -belowTolerance;

        // Safety net: if we are horizontally on target but can never satisfy the vertical check
        // (e.g. the requested Y is inside terrain), treat it as reached rather than pace forever.
        if (!followingPath && !reached && horizontallyClose && dy < 0.5D
                && ++this.closeTicks > CLOSE_ENOUGH_TICKS) {
            reached = true;
        } else if (!horizontallyClose) {
            this.closeTicks = 0;
        }

        if (reached) {
            if (this.waypoints != null && !this.waypoints.isEmpty()) {
                // Mid-path: move on to the next waypoint without reporting full arrival.
                this.advanceWaypoint();
                return;
            }
            this.arrived = true;
            this.target = null;
            this.stopInputs();
            return;
        }

        // Face the target, then push forward at full strength.
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        bot.setYRot(Mth.wrapDegrees(yaw));

        // Progress check for stuck detection.
        double moved = Math.hypot(bot.getX() - this.lastX, bot.getZ() - this.lastZ);
        this.lastX = bot.getX();
        this.lastZ = bot.getZ();
        boolean blocked = moved < STUCK_EPSILON;
        if (blocked) {
            this.stuckTicks++;
        } else {
            this.stuckTicks = 0;
        }

        // Progress toward the target, which drift cannot fake.
        double remaining = Math.hypot(bot.getX() - this.target.x, bot.getZ() - this.target.z);
        if (remaining < this.bestDistanceToTarget - 0.25D) {
            this.bestDistanceToTarget = remaining;
            this.noProgressTicks = 0;
        } else if (++this.noProgressTicks > NO_PROGRESS_TICKS) {
            if (LOG.isInfoEnabled()) {
                LOG.info("Bot gave up walking to {} after {} ticks without getting closer "
                        + "(best {}+ blocks away); it is {}+ blocks out",
                        String.format("%.1f, %.1f", this.target.x, this.target.z),
                        NO_PROGRESS_TICKS, String.format("%.1f", this.bestDistanceToTarget),
                        String.format("%.1f", remaining));
            }
            this.arrived = false;
            this.target = null;
            this.waypoints = null;
            this.stopInputs();
            return;
        }

        bot.xxa = 0.0F;
        bot.zza = 1.0F;
        bot.setSprinting(horizontal > SPRINT_MIN_DISTANCE && !blocked);

        // A door in the way is opened, not climbed or jumped at.
        //
        // The pathfinder now treats a doorway as passable, which means the bot walks straight into
        // closed doors as a matter of course. Without this it would bump, back off, bump again, and
        // eventually declare itself stuck - the classic "bot cannot leave the house" behaviour.
        if (blocked && this.stuckTicks >= STUCK_TICKS_BEFORE_DOOR) {
            this.openDoorInTheWay(bot);
        }

        // A path waypoint one block up is an explicit jump, not a collision accident. Waiting for
        // the generic stuck detector was unreliable: tiny shuffles against the stair face kept
        // resetting stuckTicks, so a bot could climb the first tread and push against the second
        // forever. This is the same input a player supplies while walking up rough stairs.
        boolean climbingToHigherWaypoint = dy > 0.45D && horizontal < 1.75D;
        if (climbingToHigherWaypoint && bot.onGround()) {
            // A real client communicates the jump impulse; merely leaving the boolean input high
            // proved unreliable for a headless ServerPlayer on consecutive one-block treads.
            // Applying vanilla's own jumpFromGround is the server-side equivalent and still lets
            // ordinary velocity, collision, exhaustion and gravity handle the jump.
            bot.jumpFromGround();
            bot.setJumping(false);
        } else if (this.stuckTicks >= STUCK_TICKS_BEFORE_JUMP && bot.onGround()) {
            bot.setJumping(true);
        } else if (!climbingToHigherWaypoint && this.stuckTicks < STUCK_TICKS_BEFORE_JUMP) {
            bot.setJumping(false);
        }

        if (this.stuckTicks > MAX_STUCK_TICKS) {
            // Out of ideas with straight-line steering: give up so the caller can replan.
            this.arrived = false;
            this.target = null;
            this.stopInputs();
        }
    }

    /**
     * Keep the bot from drowning.
     *
     * <p>A bot has no client, so the automatic things a player does without thinking have to be
     * written down. Swimming is one of them, and its absence was fatal: nothing in this class ever
     * set {@code jumping} while the bot was in water, and {@code jumping} is the <em>only</em> input
     * that produces vertical thrust in a fluid — {@code LivingEntity.aiStep} routes it to
     * {@code jumpInFluid(WATER)}, which is what every player is doing when they hold the space bar
     * to swim up.
     *
     * <p>Without it the bot sank to the riverbed, walked along the bottom chasing its target, and
     * drowned there with full lungs of water and no idea anything was wrong. Observed in production:
     * three drownings in one session, versus nine deaths from mobs.
     *
     * <p>Two stages, because they solve different problems:
     * <ol>
     *   <li><b>Eyes under water</b> — thrust straight up and nothing else. Horizontal input here is
     *       actively harmful: it drives the bot under overhangs and keeps it pinned there.</li>
     *   <li><b>Head out, lungs not full</b> — it is afloat but still in trouble, so steer to the
     *       nearest dry standable block rather than treading water until the air runs out again.</li>
     * </ol>
     *
     * <p>Deliberately a reflex rather than something the model asks for. A model turn takes seconds
     * and drowning kills in about fifteen; by the time the LLM had been consulted the bot would
     * already be dead.
     *
     * @return true if this tick's inputs are owned by the reflex
     */
    private boolean escapeWater(Player bot) {
        boolean submerged = bot.isEyeInFluid(FluidTags.WATER);
        boolean lowOnAir = bot.getAirSupply() < bot.getMaxAirSupply();
        if (!submerged && !lowOnAir) {
            this.shoreTarget = null;
            this.shoreRescanIn = 0;
            return false;
        }
        if (bot.isRemoved() || bot.isDeadOrDying() || bot.isPassenger() || bot.getAbilities().flying) {
            return false;
        }

        // Swim up.
        bot.setSprinting(false);
        bot.setJumping(true);

        if (submerged) {
            // Rising is the whole job. Hold horizontal input at zero so the bot cannot wedge itself
            // under a ledge it will then be unable to leave.
            bot.xxa = 0.0F;
            bot.zza = 0.0F;
            return true;
        }

        // Afloat but not safe: head for land.
        if (this.shoreRescanIn-- <= 0 || this.shoreTarget == null) {
            this.shoreTarget = this.findShore(bot);
            this.shoreRescanIn = SHORE_RESCAN_TICKS;
        }
        Vec3 shore = this.shoreTarget;
        if (shore == null) {
            // Nowhere to go (mid-ocean, or a pool with no reachable edge). Keep afloat and stay put;
            // the air refills at the surface, so this is survivable even if it is not progress.
            bot.xxa = 0.0F;
            bot.zza = 0.0F;
            return true;
        }

        double dx = shore.x - bot.getX();
        double dz = shore.z - bot.getZ();
        if (Math.hypot(dx, dz) < 1.0D) {
            // Effectively ashore; let the normal logic resume on the next tick.
            this.shoreTarget = null;
            bot.xxa = 0.0F;
            bot.zza = 0.0F;
            return true;
        }

        bot.setYRot(Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz))));
        bot.xxa = 0.0F;
        bot.zza = 1.0F;
        return true;
    }

    /**
     * The nearest dry block the bot could stand on, or null if there is none within range.
     *
     * <p>Scans outward from the bot and scores by squared distance with a penalty on vertical
     * offset, so a close bank is preferred over a distant one and a small climb is preferred over a
     * cliff. Only run occasionally while escaping: it is a few hundred block lookups, which is
     * fine for a rare emergency but not for every tick of a long swim.
     */
    @Nullable
    private Vec3 findShore(Player bot) {
        if (!(bot.level() instanceof ServerLevel level)) {
            return null;
        }
        BlockPos origin = bot.blockPosition();
        Vec3 best = null;
        double bestScore = Double.MAX_VALUE;

        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -SHORE_SEARCH_RADIUS; dx <= SHORE_SEARCH_RADIUS; dx++) {
                for (int dz = -SHORE_SEARCH_RADIUS; dz <= SHORE_SEARCH_RADIUS; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    if (!level.getFluidState(candidate).isEmpty()) {
                        continue;
                    }
                    if (!PathFinder.canStandAt(level, candidate)) {
                        continue;
                    }
                    double score = (double) (dx * dx + dz * dz) + dy * dy * 4.0D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = Vec3.atBottomCenterOf(candidate);
                    }
                }
            }
        }
        return best;
    }

    /**
     * Open a shut door, trapdoor or gate that is blocking the way.
     *
     * <p>Looks only at cells the bot could actually be walking into - the ones within arm's reach -
     * and only opens things that are shut, so it can never close a door the bot already went through.
     */
    private void openDoorInTheWay(Player bot) {
        if (!(bot instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                || !(bot.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos origin = bot.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-2, -1, -2), origin.offset(2, 2, 2))) {
            if (!com.melody.mcagent.rt.action.Actions.isClosedOpenable(level.getBlockState(pos))) {
                continue;
            }
            if (!com.melody.mcagent.rt.action.Actions.canReach(serverPlayer, pos)) {
                continue;
            }
            double distance = origin.distSqr(pos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }

        if (best != null && com.melody.mcagent.rt.action.Actions.openIfClosed(serverPlayer, best)) {
            // Opening counts as progress: without this the driver would give up on the waypoint
            // during the tick or two the door takes to swing.
            this.stuckTicks = 0;
            this.openedDoors++;
        }
    }

    /** Point the bot's head at a world position (used for observation and interaction). */
    public static void lookAt(Player bot, Vec3 point) {        Vec3 delta = point.subtract(bot.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
        bot.setYRot(Mth.wrapDegrees(yaw));
        bot.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
    }
}
