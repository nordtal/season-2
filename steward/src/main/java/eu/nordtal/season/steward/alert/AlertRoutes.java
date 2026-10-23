package eu.nordtal.season.steward.alert;

import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertChannel;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.alert.Measure;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.steward.auth.Sessions;
import eu.nordtal.season.steward.journal.Journaling;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Serves {@code /api/alerts}: the current alerts and the channel preferences of the admin. */
public final class AlertRoutes {

    private final Function<Context, Sessions.Session> sessions;
    private final AlertMonitor monitor;
    private final AlertPreferences preferences;

    public AlertRoutes(
            final Function<Context, Sessions.Session> sessions,
            final AlertMonitor monitor,
            final AlertPreferences preferences) {
        this.sessions = sessions;
        this.monitor = monitor;
        this.preferences = preferences;
    }

    public void current(final Context ctx) {
        ctx.json(read());
    }

    /** Holds the body of {@code GET /api/alerts}. {@code checkedAt} is null before the first reading. */
    public record Alerts(
            @Nullable Instant checkedAt, @Nullable String unreadable, Alert.Level level, List<CurrentAlert> alerts) {}

    /** Holds an alert as the page shows it, its title and lines rendered from its rows. */
    public record CurrentAlert(
            AlertType type,
            Alert.Level level,
            String subject,
            MessageRef title,
            List<MessageRef> detail,
            List<Measure> measured,
            String path) {

        public static CurrentAlert of(final Alert alert) {
            return new CurrentAlert(
                    alert.type(),
                    alert.level(),
                    alert.subject(),
                    alert.title(),
                    alert.lines(),
                    alert.measured(),
                    alert.path());
        }
    }

    public Alerts read() {
        final AlertMonitor.Snapshot now = monitor.snapshot();
        return new Alerts(
                now.checkedAt(),
                now.unreadable(),
                now.level(),
                now.alerts().stream().map(CurrentAlert::of).toList());
    }

    public void preferences(final Context ctx) {
        final Sessions.Session who = sessions.apply(ctx);
        final Map<String, Map<String, Boolean>> answer = new LinkedHashMap<>();
        preferences.of(who.signedInDiscordId()).forEach((type, channels) -> {
            final Map<String, Boolean> shown = new LinkedHashMap<>();
            channels.forEach((channel, wanted) -> shown.put(channel.key(), wanted));
            answer.put(type.key(), shown);
        });
        ctx.json(answer);
    }

    public record AlertPreference(AlertType type, AlertChannel channel, boolean enabled) {}

    public void setPreference(final Context ctx) {
        final Sessions.Session who = sessions.apply(ctx);
        final PreferenceBody body = ctx.bodyAsClass(PreferenceBody.class);
        final AlertType type = body == null ? null : AlertType.of(body.type);
        final AlertChannel channel = body == null ? null : AlertChannel.of(body.channel);
        if (type == null || channel == null || body.enabled == null) {
            throw new BadRequestResponse("a notification preference is a known type, a channel and an enabled flag");
        }
        preferences.set(who.signedInDiscordId(), type, channel, body.enabled);
        Journaling.line(
                ctx, who.signedInDiscordId(), new JournalDetail.AlertPreferenceSet(type, channel, body.enabled));
        ctx.json(new AlertPreference(type, channel, body.enabled));
    }

    private static final class PreferenceBody {
        private @Nullable String type;
        private @Nullable String channel;
        /** Boxed so that a missing field is a bad request, not false. */
        private @Nullable Boolean enabled;
    }
}
