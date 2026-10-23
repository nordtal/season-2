package eu.nordtal.season.steward.stack;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.common.time.TestScheduler;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.document.DocumentStore;
import eu.nordtal.season.database.message.MessageOverrideStore;
import eu.nordtal.season.database.online.OnlineDirectory;
import eu.nordtal.season.database.online.OnlineRoster;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.steward.ErrorHandlers;
import eu.nordtal.season.steward.settings.ServerTrial;
import eu.nordtal.season.steward.texts.WebTexts;
import eu.nordtal.season.stewardagent.AgentStandIn;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.json.JavalinGson;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;

/** Serves the routes of {@link StackApi} over a stand-in agent, without a gate. */
public final class StackServer {

    /** A caller that stays signed in. */
    static final Caller ALWAYS = new Caller() {
        @Override
        public Actor actor(final Context ctx) {
            return Actor.STEWARD;
        }

        @Override
        public boolean stillSignedIn(final Context ctx) {
            return true;
        }
    };

    private StackServer() {}

    /** Returns the API over {@code agent} and a fresh database with a nightly schedule that never fires. */
    static StackApi api(final AgentStandIn agent) {
        final StackApi.Nightly nightly = new StackApi.Nightly(
                "04:45",
                List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"),
                "05:15",
                List.of(),
                ZoneId.of("Europe/Berlin"));
        return over(
                new AgentClient(agent.client()),
                FakeDirectories.updates(),
                () -> nightly,
                TestDatabase.fresh().dataSource());
    }

    /** Returns the API over {@code agent} with its stores in {@code database}, wired as {@code Steward} wires it. */
    public static StackApi over(
            final AgentClient agent,
            final UpdateDirectory updates,
            final Supplier<StackApi.Nightly> nightly,
            final DataSource database) {
        final Clock clock = Clock.systemUTC();
        return new StackApi(
                agent,
                updates,
                nightly,
                new ServicesApi(OnlineDirectory.using(database, clock), OnlineRoster.using(database, clock), clock),
                null,
                new PluginsForward(agent, updates),
                MessageOverrideStore.using(database),
                DocumentStore.using(database),
                SettingStore.using(database),
                ServerTrial.NONE,
                clock,
                TestScheduler.SHARED);
    }

    /** Serves {@code api} on {@code port}. */
    static Javalin start(final StackApi api, final int port) {
        return Javalin.create(cfg -> {
                    cfg.jsonMapper(new JavalinGson(Json.gson(), true));
                    cfg.startup.showJavalinBanner = false;
                    ErrorHandlers.install(cfg, WebTexts.load().messages(), Messages.load("messages/database"));
                    api.register(cfg, ALWAYS);
                })
                .start(port);
    }
}
