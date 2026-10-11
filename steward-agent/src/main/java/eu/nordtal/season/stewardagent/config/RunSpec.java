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

    /** Where the season's jars and the resource pack come from, always the newest published release. */
    @Order(1)
    @Name("Season repository")
    @Key("season-repo")
    @NoExplanationNeeded
    default String seasonRepo() {
        return "nordtal/season-2";
    }

    /** An author can rename a slug. */
    @Order(3)
    @Name("PacketEvents project")
    @Key("packetevents-project")
    @Explain("The Modrinth project id, not the slug.")
    default String packetEventsProject() {
        return "HYKaKraK";
    }

    @Order(5)
    @Name("Simple Voice Chat project")
    @Key("voicechat-project")
    @Explain("One Modrinth id for both builds; installed from a pre-release.")
    default String voiceChatProject() {
        return "9eGKb6K1";
    }

    /** Retiring it means removing it from smp's eu.nordtal.plugins label in compose.yml. */
    @Order(6)
    @Name("CoreProtect project")
    @Key("coreprotect-project")
    @Explain("Reads UNSUPPORTED while no build fits this version.")
    default String coreProtectProject() {
        return "Lu3KuzdV";
    }

    @Order(7)
    @Name("Volumes root")
    @Key("volumes-root")
    @Explain("A directory that is not mounted is reported missing.")
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

    /** An operator is waiting on the report. */
    @Order(9)
    @Name("HTTP timeout (seconds)")
    @Key("http-timeout-seconds")
    @Explain("For one API call.")
    default int httpTimeoutSeconds() {
        return 30;
    }

    /** Much larger than the HTTP timeout, because this bounds downloading a Paper jar of about 65 MB. */
    @Order(10)
    @Name("Download timeout (seconds)")
    @Key("download-timeout-seconds")
    @NoExplanationNeeded
    default int downloadTimeoutSeconds() {
        return 600;
    }

    @Order(12)
    @Name("Bootstrap")
    @Key("bootstrap")
    @Explain("Installs missing artefacts before reporting ready; never updates an existing jar.")
    default boolean bootstrap() {
        return true;
    }

    /** Fetches each version's client jar from Mojang to draw item icons, and keeps only the icons. */
    @Order(13)
    @Name("Mojang assets")
    @Key("mojang-assets")
    @NoExplanationNeeded
    default boolean mojangAssets() {
        return false;
    }

    @Order(14)
    @Name("Backup")
    @Key("backup")
    @Explain("The nightly clock is in the steward group.")
    BackupSpec backup();

    /** What a {@code BACKUP} run keeps and how long it waits. */
    @ConfigSpec
    interface BackupSpec {

        /** The newest days in full, then one a week, then one a month, counted in days rather than files. */
        @Order(5)
        @Name("Retention")
        @Key("retention")
        @NoExplanationNeeded
        RetentionSpec retention();

        /** pg_dump runs inside this service, so its version always matches. */
        @Order(6)
        @Name("Database service")
        @Key("database-service")
        @Explain("Empty turns off just the database dump.")
        default String databaseService() {
            return "postgres";
        }

        /** Never a series' newest verified one. */
        @Order(7)
        @Name("Disk budget (percent)")
        @Key("budget-percent")
        @Explain("Past it the oldest archive of the largest series is deleted.")
        default int budgetPercent() {
            return 30;
        }

        /** The refusal comes before anything stops, and the failed run alerts. */
        @Order(8)
        @Name("Free space kept (percent)")
        @Key("keep-free-percent")
        @Explain("A backup leaving less free than this is refused.")
        default int keepFreePercent() {
            return 10;
        }

        /** A FAILED result alerts the admins. */
        @Order(9)
        @Name("Patience (minutes)")
        @Key("patience-minutes")
        @Explain("Past it the snapshot gives up and restarts the servers.")
        default int patienceMinutes() {
            return 30;
        }

        /** How long a backup is kept; {@code Retention} in the backup package does the arithmetic. */
        @ConfigSpec
        interface RetentionSpec {

            /** Several runs on one day count as that one day. */
            @Order(1)
            @Name("Daily")
            @Key("daily")
            @Explain("Days, not files. Below 1 the sweep refuses.")
            default int daily() {
                return 14;
            }

            /** Counted from this week, so the first weeks overlap the daily window. */
            @Order(2)
            @Name("Weekly")
            @Key("weekly")
            @Explain("0 ends the history where the daily window ends.")
            default int weekly() {
                return 8;
            }

            /** Six months of history for the price of six archives per volume. */
            @Order(3)
            @Name("Monthly")
            @Key("monthly")
            @Explain("0 turns the monthly step off.")
            default int monthly() {
                return 6;
            }

            /** Inside the window only the disk budget deletes. */
            @Order(4)
            @Name("Collapse after (days)")
            @Key("collapse-after-days")
            @Explain("A backup taken by hand survives the nightly one this long.")
            default int collapseAfterDays() {
                return 3;
            }
        }
    }
}
