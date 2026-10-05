package eu.nordtal.season.steward.web;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertBook;
import eu.nordtal.season.database.alert.AlertChannel;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.alert.RaisedAlert;
import eu.nordtal.season.database.audit.AuditDirectory;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.steward.alert.AlertMonitor;
import eu.nordtal.season.steward.alert.AlertPreferences;
import eu.nordtal.season.steward.auth.Sessions;
import eu.nordtal.season.steward.data.Data;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** {@code /api/alerts}: what is wrong now and what was raised lately, and each admin's channels per type. */
final class AlertRoutes {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    /** How many raised alerts the page lists. */
    static final int RECENT = 20;

    private final Function<Context, Sessions.Session> sessions;
    private final @Nullable AlertMonitor monitor;
    private final @Nullable AlertBook book;
    private final @Nullable AlertPreferences preferences;
    private final @Nullable AuditDirectory audit;

    AlertRoutes(
            final Function<Context, Sessions.Session> sessions,
            final @Nullable AlertMonitor monitor,
            final @Nullable Data data,
            final @Nullable AlertPreferences preferences) {
        this.sessions = sessions;
        this.monitor = monitor;
        this.book = data == null ? null : AlertBook.using(data.dataSource());
        this.preferences = preferences;
        this.audit = data == null ? null : data.audit();
    }

    /** {@code GET /api/alerts}. */
    void current(final Context ctx) {
        ctx.json(read());
    }

    /**
     * {@code GET /api/alerts}: what is wrong now, red first, and every alert raised lately, newest first.
     *
     * {@code checkedAt} is absent before the first reading, {@code unreadable} while the last one failed.
     */
    public record Alerts(
            @Nullable Instant checkedAt,
            @Nullable String unreadable,
            Alert.Level level,
            List<Alert> alerts,
            List<RecentAlert> recent) {}

    /** One alert as it was raised, by steward or the bot. */
    public record RecentAlert(
            long id,
            Instant raised,
            String raisedBy,
            AlertType type,
            Alert.Level level,
            String subject,
            MessageRef title,
            List<MessageRef> detail,
            String path) {}

    /** What is wrong now and what was raised lately, as {@code GET /api/alerts} answers. */
    Alerts read() {
        if (monitor == null || book == null) {
            throw new RequestRefused(503, ANSWER.noDatabase(StewardTexts.Kept.ALERTS));
        }
        final AlertMonitor.Snapshot now = monitor.snapshot();
        return new Alerts(
                now.checkedAt(),
                now.unreadable(),
                now.level(),
                now.alerts(),
                book.recent(RECENT).stream().map(AlertRoutes::shown).toList());
    }

    /** {@code GET /api/alerts/preferences}: every type with its channels, for the admin who asks. */
    void preferences(final Context ctx) {
        final Sessions.Session who = sessions.apply(ctx);
        final Map<String, Map<String, Boolean>> answer = new LinkedHashMap<>();
        preferences().of(who.signedInDiscordId()).forEach((type, channels) -> {
            final Map<String, Boolean> shown = new LinkedHashMap<>();
            channels.forEach((channel, wanted) -> shown.put(channel.key(), wanted));
            answer.put(type.key(), shown);
        });
        ctx.json(answer);
    }

    /** One switch of one admin: whether alerts of a type reach them on a channel. */
    public record AlertPreference(AlertType type, AlertChannel channel, boolean enabled) {}

    /** {@code PUT /api/alerts/preferences}: one switch, for the admin who is signed in. */
    void setPreference(final Context ctx) {
        final Sessions.Session who = sessions.apply(ctx);
        final PreferenceBody body = ctx.bodyAsClass(PreferenceBody.class);
        final AlertType type = body == null ? null : AlertType.of(body.type);
        final AlertChannel channel = body == null ? null : AlertChannel.of(body.channel);
        if (type == null || channel == null || body.enabled == null) {
            throw new BadRequestResponse("a notification preference is a known type, a channel and an enabled flag");
        }
        preferences().set(who.signedInDiscordId(), type, channel, body.enabled);
        if (audit != null) {
            audit.record(who.ownLine(
                    JournalAction.SET_ALERT_PREFERENCE,
                    TEXTS.journal().setAlertPreference(type, channel, body.enabled)));
        }
        ctx.json(new AlertPreference(type, channel, body.enabled));
    }

    private AlertPreferences preferences() {
        if (preferences == null) {
            throw new RequestRefused(503, ANSWER.noDatabase(StewardTexts.Kept.PREFERENCES));
        }
        return preferences;
    }

    private static RecentAlert shown(final RaisedAlert raised) {
        final Alert alert = raised.alert();
        return new RecentAlert(
                raised.id(),
                raised.raised(),
                raised.raisedBy(),
                alert.type(),
                alert.level(),
                alert.subject(),
                alert.title(),
                alert.detail(),
                alert.path());
    }

    private static final class PreferenceBody {
        private @Nullable String type;
        private @Nullable String channel;
        /** Boxed, so a missing field is a bad request, not a false. */
        private @Nullable Boolean enabled;
    }
}
