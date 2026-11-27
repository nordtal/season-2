package eu.nordtal.season.smp.tunnel;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Pure availability evaluator for Tunnel Miner start previews, active zones and execution revalidation. */
public final class TunnelAvailability {

    public static final int MIN_FRONT_BREAKABLE = 10;
    public static final int MIN_NORMAL_TOTAL_BREAKABLE = 25;
    public static final int MIN_TURN_TOTAL_BREAKABLE = 38;

    /** Result of evaluating one planned tunnel step. */
    public enum Status {
        AVAILABLE,
        TURN_LOCKED,
        INSUFFICIENT_BLOCKS,
        PROTECTED;

        public boolean isAvailable() {
            return this == AVAILABLE;
        }
    }

    /** Break-event outcome mapped from execution revalidation. */
    public enum ExecutionOutcome {
        EXECUTE,
        FALLBACK_SINGLE_BLOCK,
        CANCEL_PROTECTED
    }

    /** Evaluated availability of all seven directional zones on an active front face. */
    public record ActiveFace(Map<TunnelZone, Status> zones) {

        public Status status(final TunnelZone zone) {
            return zones.getOrDefault(zone, Status.INSUFFICIENT_BLOCKS);
        }

        public boolean isAvailable(final TunnelZone zone) {
            return status(zone).isAvailable();
        }

        public boolean anyAvailable() {
            for (final Status status : zones.values()) {
                if (status.isAvailable()) {
                    return true;
                }
            }
            return false;
        }

        public Set<TunnelZone> disabledZones() {
            final Set<TunnelZone> disabled = EnumSet.noneOf(TunnelZone.class);
            for (final Map.Entry<TunnelZone, Status> entry : zones.entrySet()) {
                if (!entry.getValue().isAvailable()) {
                    disabled.add(entry.getKey());
                }
            }
            return disabled;
        }
    }

    private TunnelAvailability() {}

    /** Evaluates availability of an initial two-slice tunnel start plan. */
    public static Status evaluateStart(
            final TunnelPlan.StepPlan plan,
            final Predicate<BlockPos> breakable,
            final Predicate<Collection<BlockPos>> permitted) {
        return evaluateStep(plan, false, breakable, permitted);
    }

    /** Evaluates availability of one active-face directional zone plan. */
    public static Status evaluateZone(
            final TunnelFrame active,
            final TunnelZone zone,
            final TunnelPlan.StepPlan plan,
            final Predicate<BlockPos> breakable,
            final Predicate<Collection<BlockPos>> permitted) {
        return evaluateStep(plan, zone.isTurn() && active.turnsLocked(), breakable, permitted);
    }

    /** Evaluates availability of all seven zones on {@code active}. */
    public static ActiveFace evaluateActiveFace(
            final TunnelFrame active,
            final Predicate<BlockPos> breakable,
            final Predicate<Collection<BlockPos>> permitted) {
        final Map<TunnelZone, Status> statuses = new EnumMap<>(TunnelZone.class);
        for (final TunnelZone zone : TunnelZone.values()) {
            final TunnelPlan.StepPlan plan = TunnelPlan.planZone(active, zone);
            statuses.put(zone, evaluateZone(active, zone, plan, breakable, permitted));
        }
        return new ActiveFace(Map.copyOf(statuses));
    }

    /** Maps a revalidation {@link Status} to the break-event execution outcome. */
    public static ExecutionOutcome executionOutcome(final Status status) {
        return switch (status) {
            case AVAILABLE -> ExecutionOutcome.EXECUTE;
            case TURN_LOCKED, INSUFFICIENT_BLOCKS -> ExecutionOutcome.FALLBACK_SINGLE_BLOCK;
            case PROTECTED -> ExecutionOutcome.CANCEL_PROTECTED;
        };
    }

    private static Status evaluateStep(
            final TunnelPlan.StepPlan plan,
            final boolean turnLocked,
            final Predicate<BlockPos> breakable,
            final Predicate<Collection<BlockPos>> permitted) {
        if (turnLocked) {
            return Status.TURN_LOCKED;
        }
        for (final BlockPos pos : plan.frontFloor()) {
            if (!breakable.test(pos)) {
                return Status.INSUFFICIENT_BLOCKS;
            }
        }
        int frontBreakable = 0;
        for (final BlockPos pos : plan.frontFace()) {
            if (breakable.test(pos)) {
                frontBreakable++;
            }
        }
        if (frontBreakable < MIN_FRONT_BREAKABLE) {
            return Status.INSUFFICIENT_BLOCKS;
        }
        final int requiredTotal = plan.turn() ? MIN_TURN_TOTAL_BREAKABLE : MIN_NORMAL_TOTAL_BREAKABLE;
        int totalBreakable = 0;
        final Set<BlockPos> seen = new HashSet<>(plan.carveBlocks().size());
        for (final BlockPos pos : plan.carveBlocks()) {
            if (seen.add(pos) && breakable.test(pos)) {
                totalBreakable++;
            }
        }
        if (totalBreakable < requiredTotal) {
            return Status.INSUFFICIENT_BLOCKS;
        }
        return permitted.test(plan.protectionFootprint()) ? Status.AVAILABLE : Status.PROTECTED;
    }
}
