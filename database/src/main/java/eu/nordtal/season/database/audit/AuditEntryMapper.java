package eu.nordtal.season.database.audit;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.alert.AlertChannel;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.audit.JournalDetail.AccessGranted;
import eu.nordtal.season.database.audit.JournalDetail.AccessRevoked;
import eu.nordtal.season.database.audit.JournalDetail.AdminRevoked;
import eu.nordtal.season.database.audit.JournalDetail.AlertPreferenceSet;
import eu.nordtal.season.database.audit.JournalDetail.Announced;
import eu.nordtal.season.database.audit.JournalDetail.AuthenticatorRemoved;
import eu.nordtal.season.database.audit.JournalDetail.AuthenticatorUsed;
import eu.nordtal.season.database.audit.JournalDetail.BackupDownloaded;
import eu.nordtal.season.database.audit.JournalDetail.ConsoleTyped;
import eu.nordtal.season.database.audit.JournalDetail.FactorsForgotten;
import eu.nordtal.season.database.audit.JournalDetail.GrantUnanchored;
import eu.nordtal.season.database.audit.JournalDetail.KeyHeld;
import eu.nordtal.season.database.audit.JournalDetail.KeyRegistered;
import eu.nordtal.season.database.audit.JournalDetail.KeyRemoved;
import eu.nordtal.season.database.audit.JournalDetail.KeyRenamed;
import eu.nordtal.season.database.audit.JournalDetail.LaunchSet;
import eu.nordtal.season.database.audit.JournalDetail.MessagesSaved;
import eu.nordtal.season.database.audit.JournalDetail.MilestoneUnlocked;
import eu.nordtal.season.database.audit.JournalDetail.ObjectiveCompleted;
import eu.nordtal.season.database.audit.JournalDetail.PhaseSet;
import eu.nordtal.season.database.audit.JournalDetail.PlaytimeSet;
import eu.nordtal.season.database.audit.JournalDetail.PluginAdded;
import eu.nordtal.season.database.audit.JournalDetail.PluginRemoved;
import eu.nordtal.season.database.audit.JournalDetail.PushTested;
import eu.nordtal.season.database.audit.JournalDetail.RestoreAsked;
import eu.nordtal.season.database.audit.JournalDetail.RunAsked;
import eu.nordtal.season.database.audit.JournalDetail.RunCancelled;
import eu.nordtal.season.database.audit.JournalDetail.SettingsSaved;
import eu.nordtal.season.database.audit.JournalDetail.Settled;
import eu.nordtal.season.database.audit.JournalDetail.SmpStartSet;
import eu.nordtal.season.database.audit.JournalDetail.Unlinked;
import eu.nordtal.season.database.payment.PaymentMatch;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.messages.value.Money;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jspecify.annotations.Nullable;

/**
 * Maps a row of {@link AuditDao#ENTRIES}: the entry, and the detail its action reads from its family's columns.
 *
 * A missing detail row is {@code null}, and the entry reads as its action; times go through {@link OffsetDateTime}.
 */
public final class AuditEntryMapper implements RowMapper<AuditEntry> {

    @Override
    public AuditEntry map(final ResultSet rs, final StatementContext ctx) throws SQLException {
        final JournalAction action = JournalAction.valueOf(Objects.requireNonNull(rs.getString("action"), "action"));
        return new AuditEntry(
                Objects.requireNonNull(rs.getObject("id", UUID.class), "id"),
                Objects.requireNonNull(instant(rs, "occurred"), "occurred"),
                action,
                Actor.of(Objects.requireNonNull(rs.getString("actor_kind"), "actor_kind"), rs.getString("actor_id")),
                DiscordId.ofNullable(rs.getString("subject")),
                rs.getObject("mc_uuid", UUID.class),
                JournalDetail.line(action, detail(action, rs)));
    }

    private static @Nullable JournalDetail detail(final JournalAction action, final ResultSet rs) throws SQLException {
        return switch (action) {
            case GRANT_ACCESS, REVOKE_ACCESS, SET_PLAYTIME, UNLINK, SETTLE, REVOKE_ADMIN, UNANCHORED_GRANT ->
                person(action, rs);
            case SET_PHASE, SET_LAUNCH, SET_SMP_START, ANNOUNCE, COMPLETE_OBJECTIVE, UNLOCK_MILESTONE ->
                season(action, rs);
            case REGISTER_KEY,
                    RENAME_KEY,
                    HELD_KEY,
                    REMOVE_KEY,
                    USED_AUTHENTICATOR,
                    REMOVE_AUTHENTICATOR,
                    FORGET_FACTORS,
                    WEB_PUSH_TEST,
                    SET_ALERT_PREFERENCE -> security(action, rs);
            case CONSOLE,
                    SAVE_SETTINGS,
                    SAVE_MESSAGES,
                    ADD_PLUGIN,
                    REMOVE_PLUGIN,
                    REQUEST_RUN,
                    CANCEL_RUN,
                    DOWNLOAD_BACKUP -> stack(action, rs);
            case ADMIN_ROOT,
                    GRANT_ADMIN,
                    ENFORCE_PACK,
                    EXEMPT_PACK,
                    LINK,
                    ADD_AUTHENTICATOR,
                    WEB_PUSH_SUBSCRIBE,
                    WEB_PUSH_UNSUBSCRIBE,
                    START_GAME,
                    LINK_CODES_EXHAUSTED -> null;
        };
    }

    /** Access, payments and the admin tree. */
    private static @Nullable JournalDetail person(final JournalAction action, final ResultSet rs) throws SQLException {
        return switch (action) {
            case GRANT_ACCESS -> {
                final Integer days = rs.getObject("grant_days", Integer.class);
                yield days == null ? null : new AccessGranted(days, Objects.requireNonNull(instant(rs, "grant_until")));
            }
            case REVOKE_ACCESS -> {
                final Integer grants = rs.getObject("grants", Integer.class);
                yield grants == null ? null : new AccessRevoked(grants);
            }
            case SET_PLAYTIME -> {
                final Long seconds = rs.getObject("playtime_seconds", Long.class);
                yield seconds == null ? null : new PlaytimeSet(Duration.ofSeconds(seconds));
            }
            case UNLINK -> {
                final Boolean selfService = rs.getObject("self_service", Boolean.class);
                yield selfService == null ? null : new Unlinked(selfService);
            }
            case SETTLE -> {
                final Integer days = rs.getObject("booked_days", Integer.class);
                final Integer received = rs.getObject("matched_cents", Integer.class);
                yield days == null
                        ? null
                        : new Settled(
                                Objects.requireNonNull(rs.getObject("payment", UUID.class), "payment"),
                                Objects.requireNonNull(rs.getString("reference"), "reference"),
                                PaymentMatch.valueOf(Objects.requireNonNull(rs.getString("matched_by"), "matched_by")),
                                days,
                                rs.getInt("ordered"),
                                received == null ? null : Money.euroCents(received),
                                Money.euroCents(rs.getInt("donation_cents")));
            }
            case REVOKE_ADMIN -> new AdminRevoked(strings(rs.getArray("below")));
            case UNANCHORED_GRANT -> {
                final Instant from = instant(rs, "valid_from");
                yield from == null ? null : new GrantUnanchored(from);
            }
            default -> throw new IllegalArgumentException(action + " is not a person action");
        };
    }

    /** The season's dates and what the game reached. */
    private static @Nullable JournalDetail season(final JournalAction action, final ResultSet rs) throws SQLException {
        return switch (action) {
            case SET_PHASE -> {
                final String from = rs.getString("from_phase");
                yield from == null
                        ? null
                        : new PhaseSet(
                                SeasonPhase.valueOf(from),
                                SeasonPhase.valueOf(Objects.requireNonNull(rs.getString("to_phase"))),
                                rs.getString("reason"));
            }
            case SET_LAUNCH -> new LaunchSet(instant(rs, "opens"));
            case SET_SMP_START -> new SmpStartSet(instant(rs, "starts"), rs.getLong("moved_grants"));
            case ANNOUNCE -> new Announced(strings(rs.getArray("languages")));
            case COMPLETE_OBJECTIVE -> {
                final String objective = rs.getString("objective");
                yield objective == null ? null : new ObjectiveCompleted(objective);
            }
            case UNLOCK_MILESTONE -> {
                final String milestone = rs.getString("milestone");
                yield milestone == null ? null : new MilestoneUnlocked(milestone);
            }
            default -> throw new IllegalArgumentException(action + " is not a season action");
        };
    }

    /** Second factors and alert routes. */
    private static @Nullable JournalDetail security(final JournalAction action, final ResultSet rs)
            throws SQLException {
        return switch (action) {
            case REGISTER_KEY, RENAME_KEY, HELD_KEY, REMOVE_KEY -> {
                final String label = rs.getString("label");
                if (label == null) {
                    yield null;
                }
                yield switch (action) {
                    case REGISTER_KEY -> new KeyRegistered(label);
                    case RENAME_KEY -> new KeyRenamed(label);
                    case HELD_KEY ->
                        new KeyHeld(
                                label, rs.getBoolean("unlocked"), Duration.ofSeconds(rs.getLong("key_hold_seconds")));
                    default -> new KeyRemoved(label, rs.getInt("keys_left"));
                };
            }
            case USED_AUTHENTICATOR -> {
                final Long hold = rs.getObject("factor_hold_seconds", Long.class);
                yield hold == null ? null : new AuthenticatorUsed(Duration.ofSeconds(hold));
            }
            case REMOVE_AUTHENTICATOR -> {
                final Integer keys = rs.getObject("keys", Integer.class);
                yield keys == null ? null : new AuthenticatorRemoved(keys);
            }
            case FORGET_FACTORS -> {
                final Integer keys = rs.getObject("keys", Integer.class);
                yield keys == null
                        ? null
                        : new FactorsForgotten(keys, rs.getBoolean("authenticator"), rs.getInt("sessions"));
            }
            case WEB_PUSH_TEST -> {
                final String alert = rs.getString("alert");
                yield alert == null ? null : new PushTested(AlertType.valueOf(alert));
            }
            case SET_ALERT_PREFERENCE -> {
                final String alert = rs.getString("alert");
                yield alert == null
                        ? null
                        : new AlertPreferenceSet(
                                AlertType.valueOf(alert),
                                AlertChannel.valueOf(Objects.requireNonNull(rs.getString("channel"))),
                                rs.getBoolean("enabled"));
            }
            default -> throw new IllegalArgumentException(action + " is not a security action");
        };
    }

    /** The services and the runs Steward asked for. */
    private static @Nullable JournalDetail stack(final JournalAction action, final ResultSet rs) throws SQLException {
        final String service = rs.getString("service");
        final Long run = rs.getObject("run", Long.class);
        final String runKind = rs.getString("run_kind");
        return switch (action) {
            case CONSOLE -> {
                final String command = rs.getString("command");
                yield service == null || command == null ? null : new ConsoleTyped(service, command);
            }
            case SAVE_SETTINGS -> {
                final String group = rs.getString("setting_group");
                yield service == null || group == null ? null : new SettingsSaved(service, group);
            }
            case SAVE_MESSAGES -> {
                final String bundle = rs.getString("bundle");
                yield bundle == null ? null : new MessagesSaved(bundle);
            }
            case ADD_PLUGIN -> {
                final String artifact = rs.getString("artifact");
                yield service == null || artifact == null ? null : new PluginAdded(service, artifact);
            }
            case REMOVE_PLUGIN -> {
                final String artifact = rs.getString("run_artifact");
                yield run == null || service == null || artifact == null
                        ? null
                        : new PluginRemoved(run, service, artifact);
            }
            case REQUEST_RUN -> {
                if (run == null || runKind == null) {
                    yield null;
                }
                final UpdateKind kind = UpdateKind.valueOf(runKind);
                final String archive = rs.getString("run_archive");
                yield kind == UpdateKind.RESTORE && archive != null
                        ? new RestoreAsked(run, archive)
                        : new RunAsked(run, kind, strings(rs.getArray("run_services")));
            }
            case CANCEL_RUN ->
                run == null || runKind == null ? null : new RunCancelled(run, UpdateKind.valueOf(runKind));
            case DOWNLOAD_BACKUP -> {
                final String archive = rs.getString("archive");
                yield archive == null ? null : new BackupDownloaded(archive, rs.getObject("size_bytes", Long.class));
            }
            default -> throw new IllegalArgumentException(action + " is not a stack action");
        };
    }

    private static @Nullable Instant instant(final ResultSet rs, final String column) throws SQLException {
        final OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }

    private static List<String> strings(final @Nullable Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
