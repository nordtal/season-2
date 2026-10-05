package eu.nordtal.season.stewardagent.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

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
    @Explain("Where season 2's jars and the resource pack come from, always the newest published release.")
    default String seasonRepo() {
        return "nordtal/season-2";
    }

    @Order(3)
    @Name("PacketEvents project")
    @Key("packetevents-project")
    @Explain("The Modrinth project id, not the slug, since an author can rename a slug.")
    default String packetEventsProject() {
        return "HYKaKraK";
    }

    @Order(5)
    @Name("Simple Voice Chat project")
    @Key("voicechat-project")
    @Explain(
            "One Modrinth id resolves both the server and proxy voice builds, and is the one artefact installed from a pre-release.")
    default String voiceChatProject() {
        return "9eGKb6K1";
    }

    @Order(6)
    @Name("CoreProtect project")
    @Key("coreprotect-project")
    @Explain(
            "Resolves UNSUPPORTED rather than failing while there is no build for this version. Retiring it means removing it from smp's eu.nordtal.plugins label in compose.yml.")
    default String coreProtectProject() {
        return "Lu3KuzdV";
    }

    @Order(7)
    @Name("Volumes root")
    @Key("volumes-root")
    @Explain("A directory that is not mounted here is reported missing rather than invented.")
    default String volumesRoot() {
        return "/volumes";
    }

    @Order(8)
    @Name("GitHub token")
    @Key("github-token")
    @NoExplanationNeeded
    default String githubToken() {
        return "";
    }

    @Order(9)
    @Name("HTTP timeout (seconds)")
    @Key("http-timeout-seconds")
    @Explain(
            "How long any single API call may wait before the run gives up, since an operator is waiting on the report.")
    default int httpTimeoutSeconds() {
        return 30;
    }

    @Order(10)
    @Name("Download timeout (seconds)")
    @Key("download-timeout-seconds")
    @Explain("Much larger than http-timeout-seconds, since this bounds downloading a Paper jar of about 65 MB.")
    default int downloadTimeoutSeconds() {
        return 600;
    }

    @Order(12)
    @Name("Bootstrap")
    @Key("bootstrap")
    @Explain(
            "Whether the agent installs missing artefacts before reporting ready. It never moves an existing jar to a newer version.")
    default boolean bootstrap() {
        return true;
    }

    @Order(13)
    @Name("Mojang assets")
    @Key("mojang-assets")
    @Explain("Fetches each version's client jar from Mojang to draw item icons, and keeps only the icons.")
    default boolean mojangAssets() {
        return false;
    }

    @Order(14)
    @Name("Backup")
    @Key("backup")
    @Explain(
            "How long archives are kept and how long one snapshot may take. The nightly clock is in the steward group.")
    BackupSpec backup();

    /** What a {@code BACKUP} run keeps and how long it waits. */
    @ConfigSpec
    interface BackupSpec {

        @Order(5)
        @Name("Retention")
        @Key("retention")
        @Explain("The newest days in full, then one a week, then one a month, counted in days rather than files.")
        RetentionSpec retention();

        @Order(6)
        @Name("Database service")
        @Key("database-service")
        @Explain(
                "pg_dump runs inside this service, so its version always matches. Empty turns off just the database dump.")
        default String databaseService() {
            return "postgres";
        }

        @Order(9)
        @Name("Patience (minutes)")
        @Key("patience-minutes")
        @Explain(
                "How long one volume's snapshot may run before this gives up and restarts the servers. A FAILED result alerts the admins.")
        default int patienceMinutes() {
            return 30;
        }

        /** How long a backup is kept; {@code Retention} in the backup package does the arithmetic. */
        @ConfigSpec
        interface RetentionSpec {

            @Order(1)
            @Name("Daily")
            @Key("daily")
            @Explain(
                    "Days, not files: several runs on one day count as that one day. Below 1 the sweep refuses rather than deleting everything.")
            default int daily() {
                return 14;
            }

            @Order(2)
            @Name("Weekly")
            @Key("weekly")
            @Explain(
                    "Counted from this week, so the first weeks overlap the daily window. 0 ends the history where the daily window ends.")
            default int weekly() {
                return 8;
            }

            @Order(3)
            @Name("Monthly")
            @Key("monthly")
            @Explain("Six months of history for the price of six archives per volume. 0 turns the monthly step off.")
            default int monthly() {
                return 6;
            }

            @Order(4)
            @Name("Collapse after (days)")
            @Key("collapse-after-days")
            @Explain(
                    "A backup taken by hand survives the nightly one for this many days. Nothing inside the window is ever deleted.")
            default int collapseAfterDays() {
                return 3;
            }
        }
    }
}
