package eu.nordtal.season.common.season;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The three switches of the season gate, as {@code season_state} holds them.
 * Whether the season is open follows from {@code launch} and the clock, so it opens by itself.
 *
 * @param launch when the season opens, null while it is closed
 * @param maintenance whether members wait in the waiting room
 * @param mode the active game mode
 */
public record SeasonState(@Nullable Instant launch, boolean maintenance, SeasonMode mode) {

    /**
     * The state of a process that has not read the row: launched, in maintenance, on {@code smp}.
     * Members wait and admins reach {@code smp}, and nobody sees a closed screen by mistake.
     */
    public static final SeasonState UNREAD = new SeasonState(Instant.EPOCH, true, SeasonMode.SMP);

    public SeasonState {
        Objects.requireNonNull(mode, "mode");
    }

    /** Returns whether the season is closed, counting down or launched at {@code now}. */
    public SeasonOpening opening(final Instant now) {
        if (launch == null) {
            return SeasonOpening.CLOSED;
        }
        return now.isBefore(launch) ? SeasonOpening.COUNTDOWN : SeasonOpening.LAUNCHED;
    }

    /** Returns whether the season is launched at {@code now}. */
    public boolean launched(final Instant now) {
        return opening(now) == SeasonOpening.LAUNCHED;
    }

    public SeasonState withLaunch(final @Nullable Instant at) {
        return new SeasonState(at, maintenance, mode);
    }

    public SeasonState withMaintenance(final boolean on) {
        return new SeasonState(launch, on, mode);
    }

    public SeasonState withMode(final SeasonMode next) {
        return new SeasonState(launch, maintenance, next);
    }
}
