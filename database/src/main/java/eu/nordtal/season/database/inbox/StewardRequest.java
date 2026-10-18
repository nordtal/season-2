package eu.nordtal.season.database.inbox;

import eu.nordtal.season.database.notify.Channel;
import java.util.List;

/** A run that steward is asked for. Each record is one kind and names the services it is for. */
public sealed interface StewardRequest {

    /** The run inbox table. The proxy and the bot's feed follow its rows on the same channel. */
    InboxTable<StewardRequest> TABLE = InboxTable.of("steward_inbox", Channel.UPDATE, StewardRequest.class);

    List<String> services();

    /**
     * Installs what is new and restarts what needs it.
     *
     * @param replacesLocal whether the asker confirmed that builds made on the host and in no release are replaced
     */
    record Update(List<String> services, boolean replacesLocal) implements StewardRequest {

        public Update {
            services = List.copyOf(services);
        }

        /** Creates an update that stops before it replaces a local build. */
        public Update(final List<String> services) {
            this(services, false);
        }
    }

    /** Restarts everything and installs nothing. */
    record Restart(List<String> services) implements StewardRequest {

        public Restart {
            services = List.copyOf(services);
        }
    }

    /** Backs up the volumes. */
    record Backup(List<String> services) implements StewardRequest {

        public Backup {
            services = List.copyOf(services);
        }
    }

    /** Stops the services and keeps them down. */
    record Down(List<String> services) implements StewardRequest {

        public Down {
            services = List.copyOf(services);
        }
    }

    /** Releases the holds on the services, or on every service. */
    record Start(List<String> services) implements StewardRequest {

        public Start {
            services = List.copyOf(services);
        }
    }

    /**
     * Restores one archive: a volume archive into its volume, or a database dump into the database.
     *
     * @param services empty, as the archive says which services it stops
     * @param archive the file name of the archive in the backup directory, which the asker types back to confirm
     */
    record Restore(List<String> services, String archive) implements StewardRequest {

        public Restore {
            services = List.copyOf(services);
            if (archive == null || archive.isBlank() || archive.contains("/")) {
                throw new IllegalArgumentException("a restore names one archive file, not " + archive);
            }
        }
    }

    /** Recreates the containers of the services from the images on this host. */
    record Recreate(List<String> services) implements StewardRequest {

        public Recreate {
            services = List.copyOf(services);
        }
    }

    /** Pulls the images of the services and recreates their containers. */
    record Deploy(List<String> services) implements StewardRequest {

        public Deploy {
            services = List.copyOf(services);
        }
    }

    /**
     * Removes an added plugin, its jar and its data folder, from a stopped server.
     *
     * @param services the one server
     * @param artifact the artefact id of the plugin, as the list of added plugins names it
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

    /**
     * Records what steward-agent installed on its start because nothing of it existed. It is written when done.
     *
     * @param services the services something was installed into, or meant to be
     */
    record Install(List<String> services) implements StewardRequest {

        public Install {
            services = List.copyOf(services);
        }
    }
}
