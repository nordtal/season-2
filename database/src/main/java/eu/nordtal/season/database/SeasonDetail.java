package eu.nordtal.season.database;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static eu.nordtal.season.database.JournalRows.row;

import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.messages.MessageRef;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jspecify.annotations.Nullable;

/** The journal details of the launch date, maintenance and the game mode, and of the season phase before them. */
public final class SeasonDetail {

    private SeasonDetail() {}

    private static @Nullable String given(final @Nullable String reason) {
        return reason == null || reason.isBlank() ? null : reason;
    }

    /** A new launch date, and how many access grants moved with it. */
    public record LaunchDateSet(
            @Nullable Instant from,
            Instant to,
            long movedGrants,
            @Nullable String reason) implements JournalDetail.Season {
        public LaunchDateSet {
            Objects.requireNonNull(to, "to");
            reason = given(reason);
        }

        @Override
        public MessageRef line() {
            return reason == null
                    ? TEXTS.journal().setLaunchDate(to, movedGrants)
                    : TEXTS.journal().setLaunchDateBecause(to, movedGrants, reason);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            launchRow(handle, entry, from, to, movedGrants, reason);
        }
    }

    /** The season launched now, before its date, and how many access grants started with it. */
    public record LaunchedNow(
            @Nullable Instant from,
            Instant to,
            long movedGrants,
            @Nullable String reason) implements JournalDetail.Season {
        public LaunchedNow {
            Objects.requireNonNull(to, "to");
            reason = given(reason);
        }

        @Override
        public MessageRef line() {
            return reason == null
                    ? TEXTS.journal().launchNow(movedGrants)
                    : TEXTS.journal().launchNowBecause(movedGrants, reason);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            launchRow(handle, entry, from, to, movedGrants, reason);
        }
    }

    /** The launch date of a launched season was removed, which closed it. */
    public record SeasonClosed(Instant from, @Nullable String reason) implements JournalDetail.Season {
        public SeasonClosed {
            Objects.requireNonNull(from, "from");
            reason = given(reason);
        }

        @Override
        public MessageRef line() {
            return reason == null
                    ? TEXTS.journal().closeSeason(from)
                    : TEXTS.journal().closeSeasonBecause(from, reason);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            launchRow(handle, entry, from, null, 0, reason);
        }
    }

    public record MaintenanceSet(boolean on, @Nullable String reason) implements JournalDetail.Season {
        public MaintenanceSet {
            reason = given(reason);
        }

        @Override
        public MessageRef line() {
            return reason == null
                    ? TEXTS.journal().setMaintenance(on)
                    : TEXTS.journal().setMaintenanceBecause(on, reason);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_maintenance", entry, "maintenance", on, "reason", reason);
        }
    }

    public record ModeSwitched(
            SeasonMode from, SeasonMode to, @Nullable String reason) implements JournalDetail.Season {
        public ModeSwitched {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            reason = given(reason);
        }

        @Override
        public MessageRef line() {
            return reason == null
                    ? TEXTS.journal().switchMode(from, to)
                    : TEXTS.journal().switchModeBecause(from, to, reason);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_season_mode", entry, "from_mode", from.name(), "to_mode", to.name(), "reason", reason);
        }
    }

    /** A switch of the season phase, which only older entries hold. Each phase is its stored name. */
    public record PhaseSet(String from, String to, @Nullable String reason) implements JournalDetail.Season {
        public PhaseSet {
            reason = given(reason);
        }

        @Override
        public MessageRef line() {
            return reason == null
                    ? TEXTS.journal().setPhase(shown(from), shown(to))
                    : TEXTS.journal().setPhaseBecause(shown(from), shown(to), reason);
        }

        private static String shown(final String stored) {
            return stored.toLowerCase(Locale.ROOT).replace('_', '-');
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_phase", entry, "from_phase", from, "to_phase", to, "reason", reason);
        }
    }

    /** An opening date of the season phase, which only older entries hold. */
    public record LaunchSet(@Nullable Instant opens) implements JournalDetail.Season {
        @Override
        public MessageRef line() {
            return opens == null
                    ? TEXTS.journal().clearLaunch()
                    : TEXTS.journal().setLaunch(opens);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            if (opens != null) {
                row(handle, "journal_launch", entry, "opens", opens);
            }
        }
    }

    /** A start of paid time, which only older entries hold. */
    public record SmpStartSet(@Nullable Instant starts, long movedGrants) implements JournalDetail.Season {
        @Override
        public MessageRef line() {
            return starts == null
                    ? TEXTS.journal().clearSmpStart()
                    : TEXTS.journal().setSmpStart(starts, movedGrants);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            if (starts != null) {
                row(handle, "journal_smp_start", entry, "starts", starts, "moved_grants", movedGrants);
            }
        }
    }

    private static void launchRow(
            final Handle handle,
            final UUID entry,
            final @Nullable Instant from,
            final @Nullable Instant to,
            final long movedGrants,
            final @Nullable String reason) {
        row(
                handle,
                "journal_season_launch",
                entry,
                "from_launch",
                from,
                "to_launch",
                to,
                "moved_grants",
                movedGrants,
                "reason",
                reason);
    }
}
