package eu.nordtal.season.steward.pack;

import eu.nordtal.season.database.pack.PackBuildStore;
import eu.nordtal.season.steward.auth.Gate;
import eu.nordtal.season.steward.journal.Unjournaled;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** The {@code /api/pack} routes: how the newest build of the resource pack stands, and asking for one again. */
public final class PackApi {

    private final PackBuildStore builds;

    public PackApi(final PackBuildStore builds) {
        this.builds = Objects.requireNonNull(builds, "builds");
    }

    /**
     * The newest build and the active one, the same build once the newest succeeded.
     *
     * @param latest {@code null} before the first build
     * @param active {@code null} while no build ever succeeded
     */
    public record PackStatus(
            @Nullable Build latest, @Nullable Build active) {}

    /**
     * One build, without the inputs it read.
     *
     * @param durationMs how long it took, once it ended
     * @param error the reason it failed, in the builder's words
     */
    public record Build(
            long id,
            PackBuildStore.State state,
            Instant started,
            @Nullable Instant finished,
            @Nullable String sha1,
            @Nullable Long sizeBytes,
            @Nullable Long durationMs,
            @Nullable String error) {

        static Build of(final PackBuildStore.PackBuild build) {
            return new Build(
                    build.id(),
                    build.state(),
                    build.started(),
                    build.finished(),
                    build.sha1(),
                    build.sizeBytes(),
                    build.duration() == null ? null : build.duration().toMillis(),
                    build.error());
        }
    }

    /** Returns how the builds stand now, as the route and the live stream read it. */
    public PackStatus read() {
        final @Nullable Build latest =
                builds.recent(1).stream().findFirst().map(Build::of).orElse(null);
        final @Nullable Build active = builds.active().map(Build::of).orElse(null);
        return new PackStatus(latest, active);
    }

    void status(final Context ctx) {
        ctx.json(read());
    }

    /** Asks steward-agent to build again; the answer comes through the live stream, as the build's own rows. */
    void again(final Context ctx) {
        builds.askAgain();
        ctx.status(202);
    }

    public void register(final JavalinConfig cfg) {
        cfg.routes.get("/api/pack", this::status, Gate.KEY_HELD);
        cfg.routes.post("/api/pack/build", this::again, Gate.KEY_HELD, Unjournaled.RETRY);
    }
}
