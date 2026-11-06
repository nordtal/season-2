package eu.nordtal.season.database;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static eu.nordtal.season.database.JournalRows.row;

import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.messages.MessageRef;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jspecify.annotations.Nullable;

/** The journal details of the launch date, maintenance and the game mode. */
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
