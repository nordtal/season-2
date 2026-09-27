package eu.nordtal.s2.steward.worker.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Secret;
import eu.nordtal.s2.steward.worker.plan.Topology;
import java.util.List;

/** What a {@code BACKUP} run saves and what it stops while it does. */
@ConfigSpec
public interface BackupSpec {

    @Order(1)
    @Name("Volumes")
    @Key("volumes")
    @Comment({
        "The Docker volumes to snapshot, by their real names as `docker volume ls` prints them,",
        "each read from <backup.sources-root>/<name>. A name with no mount there is a FAILED line.",
        "Leave out postgres-data (dumped instead), the output directory, and anything a start",
        "writes again, such as the proxy and limbo volumes."
    })
    @Explain(
            "The volumes' real names, prefixed by the compose project, not the compose.yml keys. A name with no read-only mount fails loudly.")
    default List<String> volumes() {
        return List.of(
                "nordtal-s2_mc-smp",
                "nordtal-s2_mc-smp-plugins",
                "nordtal-s2_mc-hunger-games-plugins",
                "nordtal-s2_bot-config",
                "nordtal-s2_steward-ui-config");
    }

    @Order(2)
    @Name("Services to stop")
    @Key("stop-services")
    @Comment({
        "The compose services stopped while the snapshot is taken, since a snapshot of a",
        "running Paper server is torn. The run stops them itself, after the player countdown.",
        "The proxy, limbo and hunger-games hold no world worth saving and keep running."
    })
    @Explain("Compose service names, taken from Topology so they follow a renamed service.")
    default List<String> stopServices() {
        return List.of(Topology.SMP, Topology.DISCORD_BOT);
    }

    @Order(3)
    @Name("Sources root")
    @Key("sources-root")
    @Comment({
        "Where the volumes being saved are mounted read-only, one directory per volume name.",
        "The compose mount enforces read-only, so a backup can never write to what it saves."
    })
    @Explain("Read-only is enforced by the compose mount, not by this setting.")
    default String sourcesRoot() {
        return "/backup-sources";
    }

    @Order(4)
    @Name("Output root")
    @Key("output-root")
    @Comment({
        "Where the archives and the database dump are written. Never one of the saved volumes,",
        "or the backups grow by their own contents every night."
    })
    @Explain("Must never be one of the volumes listed above, or the backups grow by their own contents every night.")
    default String outputRoot() {
        return "/backups";
    }

    @Order(5)
    @Name("Retention")
    @Key("retention")
    @Comment({
        "How long a backup is kept on this host: the newest days in full, then one a week,",
        "then one a month. It counts days, after collapsing each day to its last run."
    })
    @Explain("The newest days in full, then one a week, then one a month, counted in days rather than files.")
    RetentionSpec retention();

    @Order(6)
    @Name("Database service")
    @Key("database-service")
    @Comment({
        "The compose service running PostgreSQL. pg_dump runs inside it, so the client always",
        "matches the server. Empty turns the database dump off and the report says so."
    })
    @Explain("pg_dump runs inside this service, so its version always matches. Empty turns off just the database dump.")
    default String databaseService() {
        return "postgres";
    }

    @Order(7)
    @Name("Time of day")
    @Key("at")
    @Comment({
        "Local time of day the nightly backup is asked for, HH:mm, in this container's TZ.",
        "Empty means none. The zone and the next firing are logged on every start."
    })
    @Explain("Empty means no nightly backup at all, and nothing else in the stack makes one.")
    default String at() {
        return "04:45";
    }

    @Order(8)
    @Name("Days")
    @Key("days")
    @Comment({
        "Which weekdays the nightly backup runs on: full names or three-letter forms, any case.",
        "A word that is not a weekday is logged and ignored. An empty list means no nightly",
        "backup, and retention counts days, so gaps stretch the daily window."
    })
    @Explain(
            "Which weekdays the nightly backup runs on. All seven by default. An empty list means no nightly backup at all.")
    default List<String> days() {
        return List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
    }

    @Order(9)
    @Name("Patience (minutes)")
    @Key("patience-minutes")
    @Comment({
        "How long one volume's snapshot may take before the run gives up and starts the",
        "servers again. A run that ends FAILED mentions the admin role in the admin channel."
    })
    @Explain(
            "How long one volume's snapshot may run before this gives up and restarts the servers. A FAILED result pings the admin role.")
    default int patienceMinutes() {
        return 30;
    }

    @Order(10)
    @Name("Remote")
    @Key("remote")
    @Comment({
        "Where an offsite copy goes. An empty endpoint means none, so every archive stays on",
        "the same disk as its volume. Nothing uploads to it yet."
    })
    @Explain("Where a copy goes that is not on this disk. An empty endpoint means there is none.")
    RemoteSpec remote();

    /** How long a backup is kept; {@code Retention} in the backup package does the arithmetic. */
    @ConfigSpec
    interface RetentionSpec {

        @Order(1)
        @Name("Daily")
        @Key("daily")
        @Comment({
            "How many of the most recent days are kept in full. Below 1 the sweep refuses to run",
            "rather than deleting everything."
        })
        @Explain(
                "Days, not files: several runs on one day count as that one day. Below 1 the sweep refuses rather than deleting everything.")
        default int daily() {
            return 14;
        }

        @Order(2)
        @Name("Weekly")
        @Key("weekly")
        @Comment({
            "How many ISO weeks keep their newest surviving backup, counted from this week.",
            "0 turns the weekly step off."
        })
        @Explain(
                "Counted from this week, so the first weeks overlap the daily window. 0 ends the history where the daily window ends.")
        default int weekly() {
            return 8;
        }

        @Order(3)
        @Name("Monthly")
        @Key("monthly")
        @Comment({
            "How many calendar months keep their newest surviving backup, counted the same way.",
            "0 turns the monthly step off."
        })
        @Explain("Six months of history for the price of six archives per volume. 0 turns the monthly step off.")
        default int monthly() {
            return 6;
        }

        @Order(4)
        @Name("Collapse after (days)")
        @Key("collapse-after-days")
        @Comment({
            "How long several runs of one day are all kept before only the last of that day",
            "survives. Nothing inside this window is ever deleted; 0 collapses a day at the next sweep."
        })
        @Explain(
                "A backup taken by hand survives the nightly one for this many days. Nothing inside the window is ever deleted.")
        default int collapseAfterDays() {
            return 3;
        }
    }

    /** The offsite target: an S3 bucket and its two credentials, which the interface only reports as set or not. */
    @ConfigSpec
    interface RemoteSpec {

        @Order(1)
        @Name("Endpoint")
        @Key("endpoint")
        @Comment({
            "The S3 endpoint, with scheme. Empty means no offsite copy, and every other key here",
            "is then unread."
        })
        @Explain("Empty means there is no offsite copy.")
        default String endpoint() {
            return "";
        }

        @Order(2)
        @Name("Bucket")
        @Key("bucket")
        @Comment("The bucket the archives are written into.")
        @Explain("The bucket the archives are written into.")
        default String bucket() {
            return "";
        }

        @Order(3)
        @Name("Prefix")
        @Key("prefix")
        @Comment({
            "A path inside the bucket, so one bucket can hold more than one deployment.",
            "Empty writes to the root of the bucket."
        })
        @Explain("Lets one bucket hold more than one deployment. Empty writes to the root of the bucket.")
        default String prefix() {
            return "";
        }

        @Order(4)
        @Name("Access key")
        @Key("access-key")
        @Secret
        @Comment("The access key id. Sent to a browser as \"set\" or \"not set\", never as itself.")
        @Explain("Never leaves this process: the interface is told whether it is set, not what it is.")
        default String accessKey() {
            return "";
        }

        @Order(5)
        @Name("Secret key")
        @Key("secret-key")
        @Secret
        @Comment("The secret access key. Sent to a browser as \"set\" or \"not set\", never as itself.")
        @Explain("Never leaves this process: the interface is told whether it is set, not what it is.")
        default String secretKey() {
            return "";
        }
    }
}
