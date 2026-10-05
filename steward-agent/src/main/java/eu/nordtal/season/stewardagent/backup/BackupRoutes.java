package eu.nordtal.season.stewardagent.backup;

import eu.nordtal.season.internalapi.agent.AgentWire;
import io.javalin.config.JavalinConfig;
import java.nio.file.Path;

/** The backups on this host as Steward reads them: the archive list and one archive's bytes. */
public final class BackupRoutes {

    private final Path backups;

    public BackupRoutes(final Path backups) {
        this.backups = backups;
    }

    public void register(final JavalinConfig config) {
        config.routes.get(AgentWire.BACKUPS, ctx -> ctx.json(ArchiveFiles.list(backups)));
        // Streamed, never buffered: these are hundreds of megabytes.
        config.routes.get(AgentWire.BACKUP, ctx -> ArchiveFiles.download(ctx, backups, ctx.pathParam("name")));
    }
}
