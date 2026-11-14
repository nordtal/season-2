package eu.nordtal.season.database.inbox;

import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.database.notify.Channel;
import org.jspecify.annotations.Nullable;

/**
 * A run that steward is asked for. Each record is one kind; the services it is for are rows of {@code run_service}.
 */
public sealed interface StewardRequest {

    /** The run inbox table. The proxy and the bot's feed follow its rows on the same channel. */
    InboxTable<StewardRequest> TABLE = InboxTable.of("steward_inbox", Channel.UPDATE, StewardRequest.class)
            .withRows(Update.class, Restore.class, RemovePlugin.class, SwitchMode.class);

    /**
     * Installs what is new and restarts what needs it.
     *
     * @param replacesLocal whether the asker confirmed that builds made on the host and in no release are replaced
     */
    record Update(boolean replacesLocal) implements StewardRequest {

        /** Creates an update that stops before it replaces a local build. */
        public Update() {
            this(false);
        }
    }

    /** Restarts everything and installs nothing. */
    record Restart() implements StewardRequest {}

    /** Backs up the volumes. */
    record Backup() implements StewardRequest {}

    /** Stops the services and keeps them down. */
    record Down() implements StewardRequest {}

    /** Releases the holds on the services, or on every service. */
    record Start() implements StewardRequest {}

    /**
     * Restores one archive: a volume archive into its volume, or a database dump into the database.
     * It names no services, as the archive says which services it stops.
     *
     * @param archive the file name of the archive in the backup directory, which the asker types back to confirm
     */
    record Restore(String archive) implements StewardRequest {

        public Restore {
            if (archive == null || archive.isBlank() || archive.contains("/")) {
                throw new IllegalArgumentException("a restore names one archive file, not " + archive);
            }
        }
    }

    /** Recreates the containers of the services from the images on this host. */
    record Recreate() implements StewardRequest {}

    /** Pulls the images of the services and recreates their containers. */
    record Deploy() implements StewardRequest {}

    /**
     * Removes an added plugin, its jar and its data folder, from one stopped server, the one service it names.
     *
     * @param artifact the artefact id of the plugin, as the list of added plugins names it
     */
    record RemovePlugin(String artifact) implements StewardRequest {

        public RemovePlugin {
            if (artifact == null || artifact.isBlank() || artifact.contains("/") || artifact.length() > 100) {
                throw new IllegalArgumentException("a plugin is named by its artefact id, not " + artifact);
            }
        }
    }

    /**
     * Switches the active game mode, with everyone in the waiting room until the other server runs.
     * It names no services, as the two modes' servers are the scope.
     *
     * @param target the mode to switch to
     * @param reason why, as the asker gave it, or null
     */
    record SwitchMode(SeasonMode target, @Nullable String reason) implements StewardRequest {

        public SwitchMode {
            if (target == null) {
                throw new IllegalArgumentException("a mode switch names the mode it switches to");
            }
            if (reason != null && reason.isBlank()) {
                reason = null;
            }
        }
    }

    /** Sends the current resource pack to every player on the SMP, with no service stopped. */
    record LiveSwap() implements StewardRequest {}

    /** Records what steward-agent installed on its start because nothing of it existed. It is written when done. */
    record Install() implements StewardRequest {}
}
