package eu.nordtal.season.stewardagent.backup;

import eu.nordtal.season.internalapi.agent.AgentWire;
import io.javalin.config.JavalinConfig;
import java.nio.file.Path;
import java.util.Collection;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The backups on this host as Steward reads them: the archive list and one archive's bytes. */
public final class BackupRoutes {

    private static final Logger log = LoggerFactory.getLogger(BackupRoutes.class);

    private final Path backups;
    private final Supplier<Collection<String>> volumes;

    public BackupRoutes(final Path backups, final Supplier<Collection<String>> volumes) {
        this.backups = backups;
        this.volumes = volumes;
    }

    public void register(final JavalinConfig config) {
        config.routes.get(AgentWire.BACKUPS, ctx -> ctx.json(ArchiveFiles.list(backups, inBackup())));
        // The archives have hundreds of megabytes.
        config.routes.get(AgentWire.BACKUP, ctx -> ArchiveFiles.download(ctx, backups, ctx.pathParam("name")));
    }

    /**
     * While compose.yml cannot be read, every volume counts as in the backup set, so none is marked.
     */
    private java.util.function.Predicate<String> inBackup() {
        try {
            final Collection<String> set = volumes.get();
            return set::contains;
        } catch (final RuntimeException unreadable) {
            log.warn("the backup set could not be read, so no archive is marked as left out", unreadable);
            return volume -> true;
        }
    }
}
