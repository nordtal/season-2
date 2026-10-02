package eu.nordtal.s2.stewardagent.backup;

import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.Retention;
import eu.nordtal.s2.stewardagent.docker.Docker;
import io.javalin.config.JavalinConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * The backups on this host: the archive list and one archive's bytes, and the saves a run asks for.
 *
 * The volumes are tarred from read-only mounts; the database is dumped inside its own container.
 */
public final class BackupRoutes {

    /** Below this a tar of a world could not finish, so a shorter patience is a mistake, not a choice. */
    private static final Duration LEAST_PATIENCE = Duration.ofMinutes(1);

    private final TarSnapshots tars;
    private final DatabaseDump dump;
    private final Path backups;

    /**
     * Saves into {@code backups}, which the database's container mounts at the same path.
     *
     * @param sourcesRoot where the volumes being saved are mounted read-only, one directory per volume name
     */
    public BackupRoutes(
            final Docker docker, final String project, final Path sourcesRoot, final Path backups, final Clock clock) {
        this.tars = new TarSnapshots(sourcesRoot, backups, clock);
        this.dump = new DatabaseDump(docker, project, backups.toString(), clock);
        this.backups = backups;
    }

    public void register(final JavalinConfig config) {
        config.routes.get(AgentWire.BACKUPS, ctx -> ctx.json(ArchiveFiles.list(backups)));
        // Streamed, never buffered: these are hundreds of megabytes.
        config.routes.get(AgentWire.BACKUP, ctx -> ArchiveFiles.download(ctx, backups, ctx.pathParam("name")));
        config.routes.post(AgentWire.DUMP_DATABASE, this::dumpDatabase);
        config.routes.post(AgentWire.SNAPSHOT_VOLUME, this::snapshotVolume);
        config.routes.post(AgentWire.MARK_UNVERIFIED, ctx -> {
            final AgentWire.UnverifiedMark mark = body(ctx, AgentWire.UnverifiedMark.class);
            ctx.json(new AgentWire.Mark(tars.markUnverified(mark.archive(), mark.why())));
        });
        config.routes.post(AgentWire.PRUNE, ctx -> ctx.json(tars.prune(body(ctx, Retention.class))));
    }

    private void dumpDatabase(final Context ctx) {
        final AgentWire.DatabaseDump asked = body(ctx, AgentWire.DatabaseDump.class);
        if (asked.service().isBlank() || asked.role().isBlank()) {
            throw new BadRequestResponse("a dump needs the database's service and the role to dump as");
        }
        ctx.json(dump.save(asked.service(), asked.role()));
    }

    private void snapshotVolume(final Context ctx) {
        final AgentWire.VolumeSnapshot asked = body(ctx, AgentWire.VolumeSnapshot.class);
        final Duration patience = asked.patience().compareTo(LEAST_PATIENCE) < 0 ? LEAST_PATIENCE : asked.patience();
        ctx.json(tars.save(asked.volume(), patience));
    }

    /** The request's body, or a 400 that says which shape was expected. */
    private static <T> T body(final Context ctx, final Class<T> type) {
        final T body = ctx.bodyAsClass(type);
        if (body == null) {
            throw new BadRequestResponse("expected a " + type.getSimpleName() + " as the body");
        }
        return body;
    }
}
