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
        "The Docker volumes to snapshot, by their REAL names - what `docker volume ls`",
        "prints, not the keys in compose.yml. Compose prefixes every volume with the",
        "project name, which compose.yml pins as `nordtal-s2`, so the two differ by that",
        "prefix. Each one is read from <backup.sources-root>/<name>, where compose mounts",
        "it READ-ONLY; a name here with no mount there is a FAILED line naming the path.",
        "",
        "WHY THESE AND NOT THE OTHERS. mc-smp is Nordtal - a hand-built world in no",
        "repository and in no release, and the only thing here that cannot be rebuilt.",
        "bot-config is the bot's. The *-plugins volumes",
        "hold the only hand-edited files in the deployment:",
        "every plugin's config.yml and smp's milestones.yml and sounds.yml.",
        "steward-ui-config is with them too",
        "- it is where the interface's own settings live, and the one config",
        "volume the interface cannot rebuild for you.",
        "",
        "PROXY AND LIMBO ARE ABSENT FROM THIS LIST, data and plugins alike, and it is",
        "a decision rather than an omission. Neither holds anything a",
        "start does not write again: entrypoint.sh seeds velocity.toml, rewrites",
        "forwarding.secret from VELOCITY_FORWARDING_SECRET on EVERY start rather than once,",
        "PackWriter writes the proxy's pack.yml, and the limbo generates its world. The",
        "gain is not disk - it is that a backup no longer needs to stop the proxy, so it",
        "no longer moves anybody off the network to save a file nobody would miss. If a",
        "lobby is ever BUILT in the limbo by hand, mc-limbo belongs back here the same day.",
        "",
        "WHAT IS DELIBERATELY ABSENT. postgres-data is never here: a snapshot of a live",
        "PGDATA is torn, and it fails at RESTORE rather than at backup, which is the worst",
        "place for it to fail. The database is DUMPED instead, by this service, straight",
        "into backup.output-root - so it needs no entry here and the postgres-dumps volume",
        "it used to need is gone with the sidecar that wrote it (§9a).",
        "",
        "The output directory itself is never in this list either: a backup of the backups",
        "doubles every night until the disk is gone.",
        "",
        "mc-hunger-games is absent too - the arena is a folder that is copied in, so it",
        "is rebuilt rather than restored. bot-jar and steward-worker-jar are refilled by",
        "`steward-worker bootstrap`.",
        "",
        "WHERE a snapshot goes is backup.output-root, on this host. There is no offsite",
        "copy yet: §9a's Storage Box does not exist, so every archive is on the same disk",
        "as the thing it is a copy of, and what backup.retention keeps of them protects",
        "against a mistake and against nothing else."
    })
    @Explain(
            "The volumes' REAL names (docker volume ls, prefixed by the compose project), not the compose.yml keys. A name with no matching read-only mount below fails loudly rather than silently skipping.")
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
        "Which compose services are stopped while the snapshot is taken, by their compose",
        "service names - which is what the Docker daemon labels each container with.",
        "",
        "A SNAPSHOT OF A RUNNING PAPER SERVER IS A TORN ONE, and the way that surfaces is",
        "a region file that will not load, months later, on the one day somebody needs the",
        "backup. So the servers holding a saved volume go down first.",
        "",
        "THE STOPPING BELONGS TO THIS RUN AND TO NOTHING ELSE. Anything that stops these",
        "containers on a schedule of its own - a panel's backup policy, a cron job - takes",
        "the world away from whoever is standing in it with no countdown and no warning.",
        "The stopping is done here so that the thirty-second countdown every player sees",
        "runs first, and so that something is left running afterwards to say whether",
        "everything came back.",
        "",
        "limbo, hunger-games AND THE PROXY are absent: none of them holds a world worth",
        "saving, and an outage with nothing to show for it is worse than no backup. The",
        "proxy's volumes are absent too, and that",
        "is the whole point: nothing the proxy holds is saved any more, so",
        "stopping it would buy a proxy swap, two loading screens and a dead port for",
        "nothing. A backup now moves players to the waiting room and back, and no further.",
        "hunger-games' plugins/ volume is still snapshotted - a config.yml is written at",
        "enable and at reload and at no other time, so there is nothing in flight to tear.",
        "",
        "THESE ARE COMPOSE SERVICE NAMES AND ARE THEREFORE TAKEN FROM Topology RATHER THAN",
        "TYPED. A literal here was `bot` while compose.yml's service became `discord-bot`,",
        "and a name no container carries is a service that is never stopped - which is a",
        "snapshot of a running server, i.e. the exact thing this list exists to prevent."
    })
    @Explain(
            "Compose service names taken from Topology rather than typed by hand - a stale literal here once left a service running through its own snapshot, when its compose name changed and this string did not.")
    default List<String> stopServices() {
        return List.of(Topology.SMP, Topology.DISCORD_BOT);
    }

    @Order(3)
    @Name("Sources root")
    @Key("sources-root")
    @Comment({
        "Where the volumes being saved are mounted, read-only, one directory per volume",
        "name - so nordtal-s2_mc-smp is read from <sources-root>/nordtal-s2_mc-smp.",
        "",
        "READ-ONLY IS THE POINT AND IT IS A COMPOSE LINE, not a setting here: this service",
        "already writes the plugin and jar volumes, and world data is the one thing in this",
        "stack that cannot be rebuilt from the repository. A backup that can write to what",
        "it is saving is one bug away from being the thing that destroyed it.",
        "",
        "A volume named in `volumes` but not mounted here is a FAILED line in the report",
        "naming the path, never a small archive that looks like a success."
    })
    @Explain(
            "Read-only is enforced by the compose mount, not by this setting - a backup that could write to what it is saving is one bug away from being the thing that destroys it.")
    default String sourcesRoot() {
        return "/backup-sources";
    }

    @Order(4)
    @Name("Output root")
    @Key("output-root")
    @Comment({
        "Where the archives and the database dump are written. Its own volume, and NOT one",
        "of the volumes being saved - a backup directory inside a backed-up volume grows by",
        "its own contents every night until the disk is gone.",
        "",
        "This is also what the Storage Box upload reads, so everything worth shipping",
        "offsite is in one directory by construction."
    })
    @Explain(
            "Must never be one of the volumes listed above - a backup directory inside a backed-up volume grows by its own contents every night until the disk is gone.")
    default String outputRoot() {
        return "/backups";
    }

    @Order(5)
    @Name("Retention")
    @Key("retention")
    @Comment({
        "How long a backup is kept here. The staggered schedule: the newest days in full,",
        "then one a week, then one a month.",
        "",
        "IT REPLACED A FLAT `keep: 14`. That number counted FILES per volume, so three runs",
        "on one Tuesday spent three of the fourteen and a busy week silently shortened the",
        "history to a few days. The schedule below counts DAYS, and the day is collapsed to",
        "its last run first - see collapse-after-days.",
        "",
        "LOCAL RETENTION IS NOT THE OFFSITE ONE. This governs the disk in this host only.",
        "Until the Storage Box under backup.remote is filled, it is the ONLY retention",
        "there is - and copies on the same disk as the original protect against a mistake",
        "and against nothing else."
    })
    @Explain(
            "The staggered schedule: the newest days in full, then one a week, then one a month. It counts days rather than files, which a flat count could not.")
    RetentionSpec retention();

    @Order(6)
    @Name("Database service")
    @Key("database-service")
    @Comment({
        "The compose service running PostgreSQL. pg_dump is executed INSIDE it, which is",
        "how the dump can never be taken by an older client than the server - an older",
        "pg_dump refuses a newer server outright, and this makes the version match by",
        "construction rather than by somebody keeping two images in step.",
        "",
        "Empty turns the database dump off. The volume archives are unaffected, and the",
        "report says the database was not dumped rather than implying it was."
    })
    @Explain(
            "pg_dump runs INSIDE this service, so the dump can never be taken by an older client than the server. Empty turns off just the database dump - the volume archives are unaffected.")
    default String databaseService() {
        return "postgres";
    }

    @Order(7)
    @Name("Time of day")
    @Key("at")
    @Comment({
        "Local time of day the nightly backup is asked for, HH:mm. Empty means none.",
        "",
        "THE CLOCK LIVES HERE RATHER THAN IN `smp`. `serve` has exactly one protection - it",
        "does nothing at all until a row appears in update_request - so the nightly row has",
        "to be written by something with its own daily clock, and this is that something:",
        "a season where `smp` is down still gets a backup.",
        "",
        "What is kept is the part that mattered: this writes a request row and nothing",
        "else. It never claims one, never runs one, never touches a jar. Everything after",
        "the row is the same path /backup now takes, lock and countdown included.",
        "",
        "04:45 is what the SMP used, and it is kept: it is the quietest hour of this",
        "network's day. It was chosen because the farm world was reset shortly after and",
        "the reset refused to run without a recent backup behind it; the farm world reset",
        "has since moved, and the hour did not become a worse one.",
        "",
        "THE TIMEZONE IS THIS CONTAINER'S (compose sets TZ). The resolved zone and the next",
        "firing are logged on every start, because a backup that runs an hour off is a",
        "thing nobody notices until the clocks change."
    })
    @Explain("Empty means no nightly backup at all, and nothing else in the stack makes one.")
    default String at() {
        return "04:45";
    }

    @Order(8)
    @Name("Days")
    @Key("days")
    @Comment({
        "Which weekdays the nightly backup runs on. Full names or the three-letter forms,",
        "in any case; a word that is not a weekday is logged and ignored.",
        "",
        "ALL SEVEN IS THE DEFAULT AND IS WHAT EVERY DEPLOYMENT BEFORE THIS KEY DID. A file",
        "written before it existed has no list at all, which reads as every night - the",
        "schedule cannot change underneath a deployment that never chose one.",
        "",
        "An empty list is no nightly backup, exactly as an empty backup.at is, and it is",
        "logged on start rather than left to be discovered by a missing archive. That is",
        "deliberate: the alternative reading, 'empty means all of them', turns a list",
        "somebody cleared on purpose into a backup every night.",
        "",
        "backup.retention counts DAYS, not runs, so a schedule with gaps in it keeps its",
        "daily window for longer in wall-clock time - fourteen daily copies of a Monday",
        "and Thursday schedule are seven weeks, not two."
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
        "How long one volume's snapshot may take before the run gives up on it and starts",
        "the servers again.",
        "",
        "The servers are already down while this waits, so the number is a judgement about",
        "which is worse: a network down longer than it should be, or a snapshot abandoned",
        "just before it finished. Nordtal at border 4000 is several gigabytes and the first",
        "S3 upload of it is the slow one; every one after that is a Rustic delta.",
        "",
        "Thirty minutes rather than an hour, because giving up is no",
        "longer silent: a run that ends FAILED mentions the admin role in the admin channel",
        "instead of only editing an embed nobody is looking at at five in the morning. That",
        "is what makes the shorter wait safe - the network comes back sooner and somebody",
        "is told that a volume was not saved. What this must not be is infinite."
    })
    @Explain(
            "How long one volume's snapshot may run before this gives up and restarts the servers. The network stays down for the whole wait, so a FAILED result at the end pings the admin role rather than going unnoticed.")
    default int patienceMinutes() {
        return 30;
    }

    @Order(10)
    @Name("Remote")
    @Key("remote")
    @Comment({
        "WHERE A COPY GOES THAT IS NOT ON THIS DISK. Empty endpoint means there is none,",
        "which is what a fresh deployment has: every archive then lives on the same disk as",
        "the volume it is a copy of, and what backup.retention keeps of them protects",
        "against a mistake and against nothing else.",
        "",
        "THIS IS WHERE THE TARGET IS WRITTEN DOWN, AND NOT YET WHERE IT IS USED. The nightly",
        "run still only writes into backup.output-root; nothing in this service uploads yet.",
        "The keys are here rather than in setup.sh because a credential that only a shell",
        "script knows cannot be changed from the interface, and the backup page is the",
        "one place the target is read and typed. The upload itself is a separate step."
    })
    @Explain(
            "Where a copy goes that is not on this disk. Empty endpoint means there is none, and every archive then lives on the same disk as the volume it is a copy of.")
    RemoteSpec remote();

    /**
     * How long a backup is kept - the numbers; {@code Retention} in the backup package is the arithmetic.
     *
     * Four keys rather than one, because the rule is two rules: the staggered schedule every backup tool has,
     * and the one-per-day collapse that no standard tool does.
     */
    @ConfigSpec
    interface RetentionSpec {

        @Order(1)
        @Name("Daily")
        @Key("daily")
        @Comment({
            "How many of the most recent DAYS are kept in full. Fourteen is what the flat",
            "`keep` held before this block replaced it, and there is no reason to disagree",
            "with it - but it now means fourteen days rather than fourteen files.",
            "",
            "Below 1 the sweep refuses to run rather than deleting everything: the",
            "likeliest way to arrive at 0 is a key nobody set being read as one."
        })
        @Explain(
                "Fourteen DAYS, not fourteen files - several runs on one day count as that one day. Below 1 the sweep refuses rather than deleting everything.")
        default int daily() {
            return 14;
        }

        @Order(2)
        @Name("Weekly")
        @Key("weekly")
        @Comment({
            "How many ISO weeks keep their newest surviving backup, counted from this week",
            "rather than from the end of the daily window - so 8 means eight weeks of",
            "history, of which the first two are already covered by fourteen daily copies.",
            "",
            "0 turns the weekly step off, and the history then ends where daily ends."
        })
        @Explain(
                "Eight weeks of history, counted from this week - the first two of them are already covered by the daily window. 0 ends the history where the daily window ends.")
        default int weekly() {
            return 8;
        }

        @Order(3)
        @Name("Monthly")
        @Key("monthly")
        @Comment({
            "How many calendar months keep their newest surviving backup, counted the same",
            "way. Six months of a world costs six archives per volume - on this host about",
            "500 MiB each for mc-smp and kilobytes for everything else.",
            "",
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
            "How long several runs of ONE day are all kept before only the LAST of that day",
            "survives - kept a few days rather than collapsed right away. A backup taken by hand before",
            "touching something must not vanish the moment the nightly one lands, because",
            "that is the one moment somebody is still working on what they took it for.",
            "",
            "NOTHING INSIDE THIS WINDOW IS EVER DELETED by the sweep, for any reason.",
            "0 collapses a day as soon as the next sweep sees it."
        })
        @Explain(
                "A backup taken by hand before touching something survives the nightly one for this many days. Nothing inside the window is ever deleted, for any reason.")
        default int collapseAfterDays() {
            return 3;
        }
    }

    /**
     * The offsite target: an S3 bucket, and the two credentials for it.
     *
     * The two keys are {@link eu.nordtal.jcore.config.spec.annotation.Secret}, which makes them typeable from a
     * browser without being readable in one. {@code ConfigApi} sends a secret as {@code filled: true} and no
     * value, so the backup page can say that a key is set without it ever being in a browser cache, a screen
     * recording or the next XSS - and a new one can still be typed over it, because typing does not require
     * having seen the old one. The leaf-key heuristic in {@code ConfigEntry} would catch both of these names
     * anyway; the annotation is there so the masking does not depend on what the key happens to be called.
     */
    @ConfigSpec
    interface RemoteSpec {

        @Order(1)
        @Name("Endpoint")
        @Key("endpoint")
        @Comment({
            "The S3 endpoint, with scheme - https://<region>.your-objectstorage.com for a",
            "Hetzner Storage Box. Empty means no offsite copy at all, and every other key here",
            "is then unread."
        })
        @Explain(
                "Empty means there is no offsite copy - every archive then lives on the same disk as the thing it is a copy of.")
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
