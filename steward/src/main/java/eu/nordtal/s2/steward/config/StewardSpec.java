package eu.nordtal.s2.steward.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Secret;
import java.util.List;

/**
 * The {@code steward} group: where every version comes from, and where the files it compares against live.
 *
 * Every default is the real value; there is no key that pins a release, since the newest published one wins.
 */
@ConfigSpec
public interface StewardSpec {

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
        "does not retire it; editing Topology.SERVICES does."
    })
    @Explain(
            "Resolves UNSUPPORTED rather than failing while there is no build for this version. Retiring it means editing Topology.SERVICES.")
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
        "Whether `steward serve` installs what is missing before it reports ready.",
        "It only fills an empty volume and never moves an installed jar to a newer version.",
        "Turned off, the servers refuse to start until `steward bootstrap` has run."
    })
    @Explain(
            "Whether serve installs missing artefacts before reporting ready. It never moves an existing jar to a newer version.")
    default boolean bootstrap() {
        return true;
    }

    @Order(13)
    @Name("bunq")
    @Key("bunq")
    @Comment({
        "Payments, through steward-bunq, the only container that holds a bunq credential or calls bunq.",
        "A season without an account works except for buying access."
    })
    @Explain("How payments reach the bank through steward-bunq. Without a token a season sells nothing.")
    BunqSpec bunq();

    @Order(14)
    @Name("Backup")
    @Key("backup")
    @Comment({
        "The nightly volume backup: which volumes are saved and which services are stopped",
        "while they are. The clock writes a request row; see backup.at."
    })
    @Explain("What the nightly backup saves and stops. Its clock only writes a request row, like the Backup button.")
    BackupSpec backup();

    @Order(18)
    @Name("Update schedule")
    @Key("update")
    @Comment({
        "An optional clock that asks for a whole-network UPDATE on the days and at the time",
        "below, through the same request row as the Update button. Off while update.at is empty.",
        "Saving this file through Steward re-arms both clocks."
    })
    @Explain(
            "Off unless update.at is set. When set, a whole-network update is asked for on the chosen days, with the same countdown a manual one gets.")
    UpdateSpec update();

    @Order(17)
    @Name("Agent")
    @Key("agent")
    @Comment({
        "steward-agent, the one process that reaches Docker and the volumes: container state,",
        "logs, the console, backups and every stop, start and recreate. Without a token it asks nothing."
    })
    @Explain(
            "Steward's only way to the containers and the volumes. Without a token below, nothing about a container can be shown or done.")
    AgentSpec agent();

    @Order(16)
    @Name("Configs root")
    @Key("configs-root")
    @Comment({
        "Where every service's configuration is mounted in this container, one directory per",
        "compose service. Only a discovered .yml file can be reached."
    })
    @NoExplanationNeeded
    default String configsRoot() {
        return "/configs";
    }

    /** Payments: where steward-bunq answers and how often it is asked; purchases are the {@code access} group's. */
    @ConfigSpec
    interface BunqSpec {

        @Order(1)
        @Name("URL")
        @Key("url")
        @Comment("Where steward-bunq, the one process holding the bank key, answers from inside this container.")
        @NoExplanationNeeded
        default String url() {
            return "http://steward-bunq:8082";
        }

        @Order(2)
        @Name("Token")
        @Key("token")
        @Comment({
            "The shared secret sent as X-Steward-Token to steward-bunq. Empty means this container",
            "never asks the bank anything, so payments are off. Set NORDTAL_STEWARD_BUNQ_TOKEN instead."
        })
        @Secret
        @Explain("The secret steward-bunq expects. Empty turns payments off: nothing here asks the bank anything.")
        default String token() {
            return "";
        }

        @Order(3)
        @Name("Poll interval (seconds)")
        @Key("poll-interval-seconds")
        @Comment({
            "How often bunq is asked about open tabs and recent payments. Separate from the",
            "bot's database poll because this one is HTTP to a bank."
        })
        @Explain("How often the bank itself is asked. This is an HTTP call to bunq, separate from the bot's own poll.")
        default int pollIntervalSeconds() {
            return 30;
        }

        @Order(4)
        @Name("Watermark")
        @Key("watermark")
        @Comment({
            "Payments created before this instant are ignored, completely and forever.",
            "Leave it empty: the first start stores its own instant. A value here, ISO-8601 in UTC,",
            "overrides the stored one without replacing it."
        })
        @Explain("Leave empty: the first start stamps this itself. A manual value risks booking historical payments.")
        default String watermark() {
            return "";
        }

        @Order(5)
        @Name("Recent payment count")
        @Key("recent-payment-count")
        @Comment({
            "How many recent payments the fallback reference scan looks at per poll, for money",
            "that reached the account outside a tab."
        })
        @Explain("How many recent payments the fallback scan checks per poll, beyond the primary tab-matching path.")
        default int recentPaymentCount() {
            return 50;
        }
    }

    /** How this container reaches steward-agent, its only way to Docker and the volumes. */
    @ConfigSpec
    interface AgentSpec {

        @Order(1)
        @Name("URL")
        @Key("url")
        @Comment("Where steward-agent's HTTP API answers from inside this container.")
        @NoExplanationNeeded
        default String url() {
            return "http://steward-agent:8081";
        }

        @Order(2)
        @Name("Token")
        @Key("token")
        @Comment({
            "The shared secret sent as X-Steward-Token with every request to steward-agent.",
            "Empty means this container never asks. Set NORDTAL_STEWARD_AGENT_TOKEN instead."
        })
        @Secret
        @Explain(
                "The secret steward-agent expects. Empty means this container asks it nothing and draws no recreate button.")
        default String token() {
            return "";
        }

        @Order(3)
        @Name("Timeout (seconds)")
        @Key("timeout-seconds")
        @Comment({
            "How long one recreate, an image pull plus a forced recreate, may take before it is",
            "reported unfinished rather than failed."
        })
        @Explain(
                "Bounds a full image pull plus recreate, so it matches download-timeout-seconds rather than the API timeout.")
        default int timeoutSeconds() {
            return 600;
        }
    }

    /** When a whole-network {@code UPDATE} is asked for without anybody pressing the button. */
    @ConfigSpec
    interface UpdateSpec {

        @Order(1)
        @Name("At")
        @Key("at")
        @Comment("HH:mm in the network's default time zone, or empty for no scheduled update at all.")
        @Explain("Empty means no scheduled update. An admin can always start one by hand.")
        default String at() {
            return "";
        }

        @Order(2)
        @Name("Days")
        @Key("days")
        @Comment("Which weekdays the scheduled update runs on, read exactly like backup.days.")
        @Explain("Which weekdays the scheduled update runs on. Ignored while update.at is empty.")
        default List<String> days() {
            return List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
        }
    }
}
