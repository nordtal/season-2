package eu.nordtal.season.database;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.database.alert.AlertChannel;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.payment.PaymentMatch;
import eu.nordtal.season.database.update.ByteSize;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.value.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.statement.Update;
import org.jspecify.annotations.Nullable;

/** The values of one journal entry, one record per line. It is rendered when shown and never stored. */
public sealed interface JournalDetail {

    /** Returns the line this detail reads as. */
    MessageRef line();

    /** Writes the rows of this detail for {@code entry} in the transaction of the caller. */
    void insert(Handle handle, UUID entry);

    default @Nullable String service() {
        return null;
    }

    default @Nullable Long run() {
        return null;
    }

    default @Nullable UUID payment() {
        return null;
    }

    /** Returns the line of an entry, which is its action's when it has no detail. */
    static MessageRef line(final JournalAction action, final @Nullable JournalDetail detail) {
        if (detail != null) {
            return detail.line();
        }
        return switch (action) {
            case ADMIN_ROOT -> TEXTS.journal().adminRoot();
            case GRANT_ADMIN -> TEXTS.journal().grantAdmin();
            case ENFORCE_PACK -> TEXTS.journal().enforcePack();
            case EXEMPT_PACK -> TEXTS.journal().exemptPack();
            case LINK -> TEXTS.journal().link();
            case ADD_AUTHENTICATOR -> TEXTS.journal().addAuthenticator();
            case WEB_PUSH_SUBSCRIBE -> TEXTS.journal().webPushSubscribe();
            case WEB_PUSH_UNSUBSCRIBE -> TEXTS.journal().webPushUnsubscribe();
            case START_GAME -> TEXTS.journal().startGame();
            case LINK_CODES_EXHAUSTED -> TEXTS.journal().linkCodesExhausted();
            case SET_LAUNCH -> TEXTS.journal().clearLaunch();
            case SET_SMP_START -> TEXTS.journal().clearSmpStart();
            default -> TEXTS.journal().action(action);
        };
    }

    record AccessGranted(int days, Instant until) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().grantAccess(days, until);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_grant", entry, "days", days, "until", until);
        }
    }

    record AccessRevoked(int grants) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().revokeAccess(grants);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_revoke", entry, "grants", grants);
        }
    }

    record PlaytimeSet(Duration playtime) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().setPlaytime(playtime);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_playtime", entry, "seconds", playtime.toSeconds());
        }
    }

    record Unlinked(boolean selfService) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().unlink(selfService);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_unlink", entry, "self_service", selfService);
        }
    }

    /** A booking. Only the days and the donation are its own. */
    record Settled(
            UUID payment,
            String reference,
            PaymentMatch matched,
            int days,
            int ordered,
            @Nullable Money received,
            Money donation)
            implements JournalDetail {
        @Override
        public MessageRef line() {
            return received == null
                    ? TEXTS.journal().settleByHand(reference, days, ordered, donation)
                    : TEXTS.journal().settle(reference, matched, days, ordered, received, donation);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_booking", entry, "days", days, "donation_cents", donation.minor());
        }
    }

    record PhaseSet(
            SeasonPhase from, SeasonPhase to, @Nullable String reason) implements JournalDetail {
        public PhaseSet {
            reason = reason == null || reason.isBlank() ? null : reason;
        }

        @Override
        public MessageRef line() {
            return reason == null
                    ? TEXTS.journal().setPhase(from, to)
                    : TEXTS.journal().setPhaseBecause(from, to, reason);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_phase", entry, "from_phase", from.name(), "to_phase", to.name(), "reason", reason);
        }
    }

    record LaunchSet(@Nullable Instant opens) implements JournalDetail {
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

    record SmpStartSet(@Nullable Instant starts, long movedGrants) implements JournalDetail {
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

    record KeyRegistered(String label) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().registerKey(label);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_key", entry, "label", label);
        }
    }

    record KeyHeld(String label, boolean unlocked, Duration hold) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().heldKeyFor(label, unlocked, hold);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_key", entry, "label", label, "unlocked", unlocked, "hold_seconds", hold.toSeconds());
        }
    }

    record KeyRenamed(String label) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().renameKey(label);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_key", entry, "label", label);
        }
    }

    record KeyRemoved(String label, int left) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().removeKey(label, left);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_key", entry, "label", label, "keys_left", left);
        }
    }

    record AuthenticatorUsed(Duration hold) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().usedAuthenticator(hold);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_factor", entry, "hold_seconds", hold.toSeconds());
        }
    }

    record AuthenticatorRemoved(int keys) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().removeAuthenticator(keys);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_factor", entry, "keys", keys);
        }
    }

    record FactorsForgotten(int keys, boolean authenticator, int sessions) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().forgetEveryFactor(keys, authenticator, sessions);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_factor", entry, "keys", keys, "authenticator", authenticator, "sessions", sessions);
        }
    }

    record PushTested(AlertType alert) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().webPushTest(alert);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_push", entry, "alert", alert.name());
        }
    }

    record AlertPreferenceSet(AlertType alert, AlertChannel channel, boolean enabled) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().setAlertPreference(alert, channel, enabled);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_push", entry, "alert", alert.name(), "channel", channel.name(), "enabled", enabled);
        }
    }

    record ConsoleTyped(String service, String command) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().console(service, command);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_console", entry, "command", command);
        }
    }

    record SettingsSaved(String service, String group) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().saveSettings(service, group);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_setting", entry, "setting_group", group);
        }
    }

    record MessagesSaved(String bundle) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().saveMessages(bundle);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_bundle", entry, "bundle", bundle);
        }
    }

    record PluginAdded(String service, String artifact) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().addPlugin(service, artifact);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_plugin", entry, "artifact", artifact);
        }
    }

    /** A plugin removal. */
    record PluginRemoved(Long run, String service, String artifact) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().removePlugin(run, service, artifact);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {}
    }

    /** A run asked for. */
    record RunAsked(Long run, UpdateKind kind, List<String> services) implements JournalDetail {
        public RunAsked {
            services = List.copyOf(services);
        }

        @Override
        public MessageRef line() {
            return TEXTS.journal().requestRun(run, kind, services, services.size());
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {}
    }

    /** A restore asked for. */
    record RestoreAsked(Long run, String archive) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().requestRestore(run, archive);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {}
    }

    /** A countdown cancelled. */
    record RunCancelled(Long run, UpdateKind kind) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().cancelRun(run, kind);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {}
    }

    record BackupDownloaded(String archive, @Nullable Long bytes) implements JournalDetail {
        @Override
        public MessageRef line() {
            return bytes == null
                    ? TEXTS.journal().downloadBackupUnsized(archive)
                    : TEXTS.journal().downloadBackup(archive, ByteSize.of(bytes).message());
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_backup", entry, "archive", archive, "size_bytes", bytes);
        }
    }

    record Announced(List<String> languages) implements JournalDetail {
        public Announced {
            languages = List.copyOf(languages);
        }

        @Override
        public MessageRef line() {
            return TEXTS.journal().announce(languages);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            for (int position = 0; position < languages.size(); position++) {
                row(handle, "journal_language", entry, "position", position, "language", languages.get(position));
            }
        }
    }

    record ObjectiveCompleted(String objective) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().completeObjective(objective);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_objective", entry, "objective", objective);
        }
    }

    record MilestoneUnlocked(String milestone) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().unlockMilestone(milestone);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_milestone", entry, "milestone", milestone);
        }
    }

    record AdminRevoked(List<String> below) implements JournalDetail {
        public AdminRevoked {
            below = List.copyOf(below);
        }

        @Override
        public MessageRef line() {
            return TEXTS.journal().revokeAdmin(below, below.size());
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            for (int position = 0; position < below.size(); position++) {
                row(handle, "journal_admin_below", entry, "position", position, "discord_id", below.get(position));
            }
        }
    }

    record GrantUnanchored(Instant from) implements JournalDetail {
        @Override
        public MessageRef line() {
            return TEXTS.journal().unanchoredGrant(from);
        }

        @Override
        public void insert(final Handle handle, final UUID entry) {
            row(handle, "journal_unanchored", entry, "valid_from", from);
        }
    }

    /** Inserts one row into the table of a family from the column names and values in {@code pairs}. */
    private static void row(
            final Handle handle, final String table, final UUID entry, final @Nullable Object... pairs) {
        final StringJoiner columns = new StringJoiner(", ", "(entry, ", ")");
        final StringJoiner values = new StringJoiner(", ", "(:entry, ", ")");
        for (int at = 0; at < pairs.length; at += 2) {
            columns.add((String) pairs[at]);
            values.add(":" + pairs[at]);
        }
        final Update insert = handle.createUpdate("INSERT INTO " + table + " " + columns + " VALUES " + values)
                .bind("entry", Objects.requireNonNull(entry, "entry"));
        for (int at = 0; at < pairs.length; at += 2) {
            insert.bind((String) pairs[at], pairs[at + 1]);
        }
        insert.execute();
    }
}
