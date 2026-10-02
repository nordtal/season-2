package eu.nordtal.s2.stewardagent.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * The {@code runs} group: where every version comes from, where the files it compares against live, and backups.
 *
 * Every default is the real value; there is no key that pins a release, since the newest published one wins.
 */
@ConfigSpec
public interface RunSpec {

    @Order(1)
    @Name("Season repository")
    @Key("season-repo")
    @Comment({
        "The GitHub repository the season 2 jars and the resource pack come from, as",
        "owner/name. The newest published release always wins; drafts and pre-releases are skipped."
    })
    @Explain("Where season 2's jars and the resource pack come from, always the newest published release.")
    default String seasonRepo() {
        return "nordtal/season-2";
    }

    @Order(2)
    @Name("Display tags repository")
    @Key("display-tags-repo")
    @Comment("Our fork of the Text Display nametag plugin, which smp requires to enable.")
    @Explain("Required on the SMP server: smp refuses to enable without a release fetched from here.")
    default String displayTagsRepo() {
        return "nordtal/papermc-display-tags";
    }

    @Order(3)
    @Name("PacketEvents project")
    @Key("packetevents-project")
    @Comment({
        "The Modrinth project id of PacketEvents, which DisplayTags is built on.",
        "The id, not the slug, since an author can rename a slug."
    })
    @Explain("The Modrinth project id, not the slug, since an author can rename a slug.")
    default String packetEventsProject() {
        return "HYKaKraK";
    }

    @Order(5)
    @Name("Simple Voice Chat project")
    @Key("voicechat-project")
    @Comment({
        "The Modrinth project id of Simple Voice Chat. One id resolves both the paper build",
        "for the servers and the velocity build for the proxy, and it is the one artefact",
        "installed from a pre-release; see Modrinth.PRE_RELEASE_EXCEPTIONS."
    })
    @Explain(
            "One Modrinth id resolves both the server and proxy voice builds, and is the one artefact installed from a pre-release.")
    default String voiceChatProject() {
        return "9eGKb6K1";
    }

    @Order(6)
    @Name("CoreProtect project")
    @Key("coreprotect-project")
    @Comment({
        "The Modrinth project id of CoreProtect, the block logger on smp. Without a build for",
        "this Minecraft version it resolves as UNSUPPORTED and installs nothing. Blanking it",
        "does not retire it; removing it from smp's eu.nordtal.plugins label does."
    })
    @Explain(
            "Resolves UNSUPPORTED rather than failing while there is no build for this version. Retiring it means removing it from smp's eu.nordtal.plugins label in compose.yml.")
    default String coreProtectProject() {
        return "Lu3KuzdV";
    }

    @Order(7)
    @Name("Volumes root")
    @Key("volumes-root")
    @Comment({
        "Where the Minecraft volumes are mounted in this container, one directory per compose",
        "service named after it. A missing directory is reported, never created."
    })
    @Explain("A directory that is not mounted here is reported missing rather than invented.")
    default String volumesRoot() {
        return "/volumes";
    }

    @Order(8)
    @Name("GitHub token")
    @Key("github-token")
    @Comment({
        "Optional. Raises GitHub's unauthenticated limit of 60 requests per hour per IP.",
        "A fine-grained token with public read access is enough."
    })
    @NoExplanationNeeded
    default String githubToken() {
        return "";
    }

    @Order(9)
    @Name("HTTP timeout (seconds)")
    @Key("http-timeout-seconds")
    @Comment({
        "How long any single API call may take before the run gives up, so the report always",
        "arrives or says why not."
    })
    @Explain(
            "How long any single API call may wait before the run gives up, since an operator is waiting on the report.")
    default int httpTimeoutSeconds() {
        return 30;
    }

    @Order(10)
    @Name("Download timeout (seconds)")
    @Key("download-timeout-seconds")
    @Comment({
        "How long a single jar may take to download during an update run, sized",
        "for a Paper server jar of about 65 MB."
    })
    @Explain("Much larger than http-timeout-seconds, since this bounds downloading a Paper jar of about 65 MB.")
    default int downloadTimeoutSeconds() {
        return 600;
    }

    @Order(12)
    @Name("Bootstrap")
    @Key("bootstrap")
    @Comment({
        "Whether steward-agent installs what is missing before it reports ready.",
        "It only fills an empty volume and never moves an installed jar to a newer version.",
        "Turned off, the servers refuse to start until something has installed their files."
    })
    @Explain(
            "Whether the agent installs missing artefacts before reporting ready. It never moves an existing jar to a newer version.")
    default boolean bootstrap() {
        return true;
    }

    @Order(13)
    @Name("Mojang assets")
    @Key("mojang-assets")
    @Comment({
        "Whether steward-agent fetches Mojang's client jar for each Minecraft version a server",
        "runs, to draw the item icons Steward's pickers show. The jar is deleted once drawn;",
        "only the icons are kept, in the database. The installer asks once and sets",
        "NORDTAL_MOJANG_ASSETS. Turned off, the pickers show names without icons."
    })
    @Explain("Fetches each version's client jar from Mojang to draw item icons, and keeps only the icons.")
    default boolean mojangAssets() {
        return false;
    }

    @Order(14)
    @Name("Backup")
    @Key("backup")
    @Comment("What a BACKUP run keeps and how long it waits; when one is asked for is Steward's clock.")
    @Explain(
            "How long archives are kept and how long one snapshot may take. The nightly clock is in the steward group.")
    BackupSpec backup();

    /** What a {@code BACKUP} run keeps and how long it waits. */
    @ConfigSpec
    interface BackupSpec {

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
        @Explain(
                "pg_dump runs inside this service, so its version always matches. Empty turns off just the database dump.")
        default String databaseService() {
            return "postgres";
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
    }
}
