package eu.nordtal.s2.steward.web;

import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.alert.AlertBook;
import eu.nordtal.s2.database.alert.AlertChannel;
import eu.nordtal.s2.database.alert.AlertType;
import eu.nordtal.s2.database.alert.RaisedAlert;
import eu.nordtal.s2.steward.alert.AlertMonitor;
import eu.nordtal.s2.steward.alert.AlertPreferences;
import eu.nordtal.s2.steward.auth.Sessions;
import eu.nordtal.s2.steward.data.Data;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ServiceUnavailableResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** {@code /api/alerts}: what is wrong now and what was raised lately, and each admin's channels per type. */
final class AlertRoutes {

    /** How many raised alerts the page lists. */
    static final int RECENT = 20;

    private final Function<Context, Sessions.Session> sessions;
    private final @Nullable AlertMonitor monitor;
    private final @Nullable AlertBook book;
    private final @Nullable AlertPreferences preferences;

    AlertRoutes(
            final Function<Context, Sessions.Session> sessions,
            final @Nullable AlertMonitor monitor,
            final @Nullable Data data,
            final @Nullable AlertPreferences preferences) {
        this.sessions = sessions;
        this.monitor = monitor;
        this.book = data == null ? null : AlertBook.using(data.dataSource());
        this.preferences = preferences;
    }

    /** {@code GET /api/alerts}. */
    void current(final Context ctx) {
        if (monitor == null || book == null) {
            throw new ServiceUnavailableResponse("Steward has no database, so it keeps no alerts");
        }
        final AlertMonitor.Snapshot now = monitor.snapshot();
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("checkedAt", now.checkedAt() == null ? null : now.checkedAt().toString());
        answer.put("unreadable", now.unreadable());
        answer.put("level", now.level().key());
        answer.put("alerts", now.alerts().stream().map(AlertRoutes::shown).toList());
        answer.put(
                "recent", book.recent(RECENT).stream().map(AlertRoutes::shown).toList());
        ctx.json(answer);
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
        ctx.json(Map.of("type", type.key(), "channel", channel.key(), "enabled", body.enabled));
    }

    private AlertPreferences preferences() {
        if (preferences == null) {
            throw new ServiceUnavailableResponse("Steward has no database, so it keeps no preferences");
        }
        return preferences;
    }

    private static Map<String, Object> shown(final Alert alert) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", alert.type().key());
        row.put("level", alert.level().key());
        row.put("subject", alert.subject());
        row.put("title", alert.title());
        row.put("detail", alert.detail());
        row.put("path", alert.path());
        return row;
    }

    private static Map<String, Object> shown(final RaisedAlert raised) {
        final Map<String, Object> row = shown(raised.alert());
        row.put("id", raised.id());
        row.put("raised", raised.raised().toString());
        row.put("raisedBy", raised.raisedBy());
        return row;
    }

    private static final class PreferenceBody {
        private @Nullable String type;
        private @Nullable String channel;
        /** Boxed, so a missing field is a bad request, not a false. */
        private @Nullable Boolean enabled;
    }
}
