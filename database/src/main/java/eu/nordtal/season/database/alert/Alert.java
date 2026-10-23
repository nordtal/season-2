package eu.nordtal.season.database.alert;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.messages.MessageRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One thing an admin should hear of: a journal entry with a level.
 * Its title and lines are rendered from what it measured and from its detail, never stored.
 *
 * @param action the alert's action, which decides its {@link AlertType}
 * @param measured what it measured, one fact each, empty for an event and for a clear
 * @param detail the values of an event, null for a measured alert
 */
public record Alert(
        JournalAction action,
        Level level,
        List<Measure> measured,
        @Nullable JournalDetail detail) {

    /** The subject of the measure for every backup at once. */
    public static final String BACKUPS = "backups";

    /** The subject of the measure for the database dumps. */
    public static final String DUMP = "database dump";

    /** The subject of the measure for the copies off this host. */
    public static final String OFFSITE = "offsite copy";

    /** How bad an alert is. {@link #OK} clears an earlier alert of the same type. */
    public enum Level {
        OK,
        WARN,
        DOWN;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public Alert {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(level, "level");
        if (AlertType.of(action) == null) {
            throw new IllegalArgumentException(action + " is no alert");
        }
        measured = List.copyOf(measured);
    }

    /** Creates an alert of what was measured. */
    public Alert(final JournalAction action, final Level level, final List<Measure> measured) {
        this(action, level, measured, null);
    }

    /** Creates an event at {@code level}. */
    public Alert(final JournalAction action, final Level level, final JournalDetail detail) {
        this(action, level, List.of(), detail);
    }

    public AlertType type() {
        return Objects.requireNonNull(AlertType.of(action));
    }

    /** Returns what it is about: the subjects it measured, else what the action names. */
    public String subject() {
        final List<String> subjects = subjects();
        if (!subjects.isEmpty()) {
            return String.join(", ", subjects);
        }
        if (detail instanceof JournalDetail.RunFailed failed) {
            return failed.kind().name().toLowerCase(Locale.ROOT);
        }
        return action == JournalAction.SERVICE_DOWN ? "services" : action.name().toLowerCase(Locale.ROOT);
    }

    /** Returns one line without numbers, for the lock screen and the admin channel. */
    public MessageRef title() {
        if (level == Level.OK) {
            return TEXTS.alert().clear(type());
        }
        final List<String> subjects = subjects();
        if (measured.stream().allMatch(measure -> measure.gauge() == Measure.Gauge.OLDER_IMAGE)
                && !measured.isEmpty()) {
            return TEXTS.alert().olderImage(subjects, subjects.size());
        }
        if (subjects.size() > 1) {
            return TEXTS.alert().several(subjects);
        }
        if (!measured.isEmpty()) {
            return title(measured.getFirst());
        }
        return switch (action) {
            case SERVICE_DOWN -> TEXTS.alert().noServices();
            case RUN_FAILED ->
                TEXTS.run()
                        .title(
                                ((JournalDetail.RunFailed) Objects.requireNonNull(detail)).kind(),
                                UpdateReport.Stage.FAILED);
            case PAYMENT_NEEDS_LOOK -> TEXTS.alert().payment();
            case LINK_REFUSED -> TEXTS.alert().linkRefused();
            case LINK_FAILED -> TEXTS.alert().linkFailed();
            case PURCHASE_FAILED -> TEXTS.alert().purchaseFailed();
            case DM_NOT_DELIVERED -> TEXTS.alert().dm();
            case ROLE_NOT_CHANGED -> {
                final JournalDetail.RoleNotChanged role = (JournalDetail.RoleNotChanged) Objects.requireNonNull(detail);
                yield TEXTS.alert().roleNotChanged(role.role(), role.given());
            }
            case ROLE_AMBIGUOUS -> TEXTS.alert().roleAmbiguous(named());
            case ROLE_NOT_CREATED -> TEXTS.alert().roleNotCreated(named());
            case LOCK_NOT_KEPT -> TEXTS.alert().lockNotKept();
            case LOCK_WITHOUT_CHANNEL -> TEXTS.alert().lockWithoutChannel();
            default -> throw new IllegalStateException(action + " needs a measure for its title");
        };
    }

    /** Returns the lines below the title: one per measure but an older image, then the detail's. */
    public List<MessageRef> lines() {
        final List<MessageRef> lines = new ArrayList<>();
        // The title of older images names every service already.
        measured.stream()
                .filter(measure -> measure.gauge() != Measure.Gauge.OLDER_IMAGE)
                .map(Alert::line)
                .forEach(lines::add);
        if (detail != null) {
            lines.addAll(detail.lines());
        } else if (action == JournalAction.LOCK_WITHOUT_CHANNEL) {
            lines.add(TEXTS.alert().nobodyLocked());
        }
        return List.copyOf(lines);
    }

    /** Returns the Steward page that can act on it. */
    public String path() {
        return switch (action) {
            case SERVICE_DOWN ->
                measured.isEmpty()
                        ? "/operations/updates"
                        : "/services/" + measured.getFirst().subject();
            case BACKUP_STALE -> "/operations/backups";
            case IMAGE_DRIFT -> "/operations/updates";
            case PACK_DEAD -> "/services/proxy";
            case RUN_FAILED -> {
                final JournalDetail.RunFailed failed = (JournalDetail.RunFailed) Objects.requireNonNull(detail);
                yield failed.kind().page(failed.run());
            }
            case PAYMENT_NEEDS_LOOK, LINK_REFUSED, PURCHASE_FAILED -> "/payments";
            case LINK_FAILED, DM_NOT_DELIVERED, ROLE_NOT_CHANGED, ROLE_AMBIGUOUS, ROLE_NOT_CREATED -> "/access";
            case LOCK_NOT_KEPT, LOCK_WITHOUT_CHANNEL -> "/services/discord-bot";
            default -> "/";
        };
    }

    private List<String> subjects() {
        return measured.stream().map(Measure::subject).distinct().toList();
    }

    private String named() {
        return ((JournalDetail.RoleNamed) Objects.requireNonNull(detail)).name();
    }

    private static MessageRef title(final Measure measure) {
        final String subject = measure.subject();
        return switch (measure.gauge()) {
            case CONTAINER_STATE -> TEXTS.alert().notRunning(subject);
            case HEALTH -> TEXTS.alert().unhealthy(subject);
            case EXIT_CODE -> TEXTS.alert().failedOnce(subject);
            case RESTARTS -> TEXTS.alert().restarting(subject);
            case BACKUP_AGE_HOURS -> backupTitle(subject, measure.value() == null);
            case DISK_PERCENT -> TEXTS.alert().disk();
            case MEMORY_PERCENT -> TEXTS.alert().memory();
            case OLDER_IMAGE -> TEXTS.alert().olderImage(List.of(subject), 1);
            case REGISTRY -> TEXTS.alert().notCompared();
            case PACK -> TEXTS.alert().deadPack();
        };
    }

    private static MessageRef backupTitle(final String subject, final boolean missing) {
        return switch (subject) {
            case BACKUPS -> TEXTS.alert().noBackup();
            case DUMP -> missing ? TEXTS.alert().noDump() : TEXTS.alert().oldDump();
            case OFFSITE -> missing ? TEXTS.alert().noOffsite() : TEXTS.alert().oldOffsite();
            default ->
                missing ? TEXTS.alert().noArchive(subject) : TEXTS.alert().oldArchive(subject);
        };
    }

    private static MessageRef line(final Measure measure) {
        final String subject = measure.subject();
        final long value = measure.value() == null ? 0 : Math.round(measure.value());
        final long limit = measure.limit() == null ? 0 : Math.round(measure.limit());
        final String state = String.valueOf(measure.state());
        return switch (measure.gauge()) {
            case CONTAINER_STATE -> TEXTS.alert().dockerState(subject, state);
            case HEALTH -> TEXTS.alert().healthFails(subject);
            case EXIT_CODE -> TEXTS.alert().exitCode(subject, value);
            case RESTARTS -> TEXTS.alert().restartedByDocker(subject, value);
            case BACKUP_AGE_HOURS ->
                measure.value() == null
                        ? TEXTS.alert().missing(subject)
                        : TEXTS.alert().age(subject, value, limit);
            case DISK_PERCENT -> TEXTS.alert().diskUse(value, limit);
            case MEMORY_PERCENT -> TEXTS.alert().memoryUse(value, limit);
            case OLDER_IMAGE -> throw new IllegalArgumentException("older images have no line");
            case REGISTRY -> TEXTS.alert().words(state);
            case PACK -> TEXTS.alert().offeredPack(state);
        };
    }
}
