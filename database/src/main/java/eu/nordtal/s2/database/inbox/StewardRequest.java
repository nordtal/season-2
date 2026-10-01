package eu.nordtal.s2.database.inbox;

import eu.nordtal.s2.database.notify.Channel;
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
}
