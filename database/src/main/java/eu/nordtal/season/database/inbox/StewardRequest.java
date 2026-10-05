package eu.nordtal.season.database.inbox;

import eu.nordtal.season.database.notify.Channel;
import java.util.List;

/** A run steward is asked for; each record is one kind, and each names the services it is for. */
public sealed interface StewardRequest {

    /** The run inbox table, whose rows the proxy and the bot's feed follow on the same channel. */
    InboxTable<StewardRequest> TABLE = InboxTable.of("steward_inbox", Channel.UPDATE, StewardRequest.class);

    /** Returns the compose services the run is for; empty is the whole network. */
    List<String> services();

    /** Installs what is new and restarts what needs it. */
    record Update(List<String> services) implements StewardRequest {

        public Update {
            services = List.copyOf(services);
        }
    }

    /** Restarts everything, installing nothing. */
    record Restart(List<String> services) implements StewardRequest {

        public Restart {
            services = List.copyOf(services);
        }
    }

    /** Backs the volumes up. */
    record Backup(List<String> services) implements StewardRequest {

        public Backup {
            services = List.copyOf(services);
        }
    }

    /** Stops the services and holds them down. */
    record Down(List<String> services) implements StewardRequest {

        public Down {
            services = List.copyOf(services);
        }
    }

    /** Releases the holds on the services, or on every one. */
    record Start(List<String> services) implements StewardRequest {

        public Start {
            services = List.copyOf(services);
        }
    }

    /**
     * Puts one archive back: a volume archive into its volume, or a database dump into the database.
     *
     * @param services empty: the archive itself says which services it stops
     * @param archive the archive's file name in the backup directory, typed back by whoever asked as a confirmation
     */
    record Restore(List<String> services, String archive) implements StewardRequest {

        public Restore {
            services = List.copyOf(services);
            if (archive == null || archive.isBlank() || archive.contains("/")) {
                throw new IllegalArgumentException("a restore names one archive file, not " + archive);
            }
        }
    }

    /** Makes the services' containers again from the images on this host. */
    record Recreate(List<String> services) implements StewardRequest {

        public Recreate {
            services = List.copyOf(services);
        }
    }

    /** Pulls the services' images and makes their containers again from them. */
    record Deploy(List<String> services) implements StewardRequest {

        public Deploy {
            services = List.copyOf(services);
        }
    }

    /**
     * Takes an added plugin, its jar and its data folder, off a server while that server is stopped.
     *
     * @param services the one server
     * @param artifact the plugin's artefact id, as the list of added plugins names it
     */
    record RemovePlugin(List<String> services, String artifact) implements StewardRequest {

        public RemovePlugin {
            services = List.copyOf(services);
            if (services.size() != 1) {
                throw new IllegalArgumentException("a plugin is removed from exactly one server, not " + services);
            }
            if (artifact == null || artifact.isBlank() || artifact.contains("/") || artifact.length() > 100) {
                throw new IllegalArgumentException("a plugin is named by its artefact id, not " + artifact);
            }
        }
    }
}
