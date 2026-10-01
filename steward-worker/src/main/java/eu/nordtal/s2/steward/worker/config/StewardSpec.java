package eu.nordtal.s2.steward.worker.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Secret;
import eu.nordtal.s2.common.Deployment;
import java.util.List;

/**
 * {@code config/steward.yml}: where every version comes from, and where the files it compares against live.
 *
 * Every default is the real value; there is no key that pins a release, since the newest published one wins.
 */
@ConfigSpec(
        header = {
            "-------------------------------------------------------------------",
            "  steward-worker: where the versions come from",
            "-------------------------------------------------------------------",
            "Resolves the newest version of everything the network runs and",
            "compares it with the jars in the volumes, only when asked.",
            "",
            "The defaults are the real values for nordtal.eu; change them only",
            "to point a test deployment elsewhere. Nothing here pins a version.",
            "",
            "Every setting can be overridden with an environment variable named",
            "NORDTAL_STEWARD_<PATH>, with '-' becoming '_', for example",
            "NORDTAL_STEWARD_SEASON_REPO. The environment wins over this file",
            "and is never written back to it."
        })
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
        "How long a single jar may take to download during `steward-worker apply`, sized",
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
        "Whether `steward-worker serve` installs what is missing before it reports ready.",
        "It only fills an empty volume and never moves an installed jar to a newer version.",
        "Turned off, the servers refuse to start until an apply has run."
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
        "The bank. This is the only container that holds a bunq credential or calls bunq.",
        "All of it is optional; a season without an account works except for buying access."
    })
    @Explain("The bunq account payments arrive in. Leave it empty for a season that sells nothing.")
    BunqSpec bunq();

    @Order(15)
    @Name("Docker")
    @Key("docker")
    @Comment({
        "The daemon this service reads for container state, health, image drift, logs, the",
        "console and metrics. It only reads, stops and starts; creating a container is steward-deployer's.",
        "Without the socket, the drift check and metrics answer that they could not look."
    })
    @Explain(
            "Read, stop and start only; creating a container belongs to steward-deployer. Without the socket this reports 'could not look'.")
    DockerSpec docker();

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
    @Name("Deployer")
    @Key("deployer")
    @Comment({
        "steward-deployer, the one process allowed to create a container. An update asks its",
        "HTTP API to pull and recreate a service whose image is out of date. Without a token it asks nothing."
    })
    @Explain(
            "Asks steward-deployer to recreate a service whose image is stale. Without a token below, it asks nothing at all.")
    DeployerSpec deployer();

    @Order(16)
    @Name("API")
    @Key("api")
    @Comment({
        "The internal API steward-ui reads this container through, so the web interface never",
        "holds the docker socket. It offers a list, a log, a search and one console line, never",
        "stop or start. It does not serve without a token."
    })
    @Explain(
            "Keeps the Docker socket away from steward-ui: a list, a log, a search and one console line. Without a token it refuses to serve.")
    ApiSpec api();

    /** bunq: the credentials, the API context file and the poll; purchase settings stay in {@code access.yml}. */
    @ConfigSpec
    interface BunqSpec {

        @Order(1)
        @Name("API key")
        @Key("api-key")
        @Comment({
            "bunq API key. Set NORDTAL_STEWARD_BUNQ_API_KEY instead of filling this in.",
            "Empty is valid, so this container logs on every start whether bunq is on."
        })
        @Secret
        @NoExplanationNeeded
        default String apiKey() {
            return "";
        }

        @Order(2)
        @Name("Account ID")
        @Key("account-id")
        @Comment({
            "The bunq monetary account id that is polled and billed. A number; the worker will",
            "not start if it is set and not numeric."
        })
        @Explain(
                "A number, not an IBAN or alias. The worker refuses to start if it is non-numeric or only half the pair is filled in.")
        default String accountId() {
            return "";
        }

        @Order(3)
        @Name("Context path")
        @Key("context-path")
        @Comment({
            "Where the bunq API context file, which holds credentials, is kept. Empty means the",
            "working directory. Never copy one in: bunq binds it to the device that registered it."
        })
        @Explain(
                "Where the bunq API context file is kept. Never copy one in, since bunq binds it to the device that registered it.")
        default String contextPath() {
            return "";
        }

        @Order(4)
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

        @Order(5)
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

        @Order(6)
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

    /** Where the daemon is, and which compose project is ours. */
    @ConfigSpec
    interface DockerSpec {

        @Order(1)
        @Name("Socket")
        @Key("socket")
        @Comment({
            "The unix socket of the Docker daemon, as this container sees it. A missing path is",
            "reported once, and everything that needs the daemon then answers `could not look`."
        })
        @Explain("Missing here is not a startup failure: everything needing the daemon then answers 'could not look'.")
        default String socket() {
            return "/var/run/docker.sock";
        }

        @Order(2)
        @Name("Compose project")
        @Key("project")
        @Comment({
            "The compose project name. Containers are <project>-<service>-1, and this separates",
            "ours from anything else on the same daemon. It is written down, never guessed."
        })
        @Explain(
                "Written down rather than guessed from labels, so a second copy of the stack cannot change what it matches.")
        default String project() {
            return Deployment.PROJECT;
        }

        @Order(3)
        @Name("Metrics")
        @Key("metrics")
        @Comment({
            "Whether the 30-second sampler runs. Its table is in the nightly backup, so turning it",
            "off also makes every snapshot smaller, and the start page has no curves."
        })
        @Explain(
                "The metrics table rides inside the nightly database backup; turning it off shrinks every snapshot and drops the curves.")
        default boolean metrics() {
            return true;
        }
    }

    /** The port and the shared secret of the internal API. */
    @ConfigSpec
    interface ApiSpec {

        @Order(1)
        @Name("Port")
        @Key("port")
        @Comment("The port inside the container, published to nothing; only steward-ui calls it.")
        @NoExplanationNeeded
        default int port() {
            return 8082;
        }

        @Order(2)
        @Name("Token")
        @Key("token")
        @Comment({
            "The shared secret steward-ui sends as X-Steward-Token. Empty means the API does not",
            "start, and update runs carry on without it. Set NORDTAL_STEWARD_API_TOKEN instead."
        })
        @Secret
        @Explain(
                "Empty disables just this internal API; update runs keep working. It comes from the environment in a real deployment.")
        default String token() {
            return "";
        }

        @Order(3)
        @Name("Configs root")
        @Key("configs-root")
        @Comment({
            "Where every service's configuration is mounted in this container, one directory",
            "per compose service. steward-ui draws the form and this service reads and writes",
            "the files, since they are 0600 root. Only a discovered .yml file can be reached."
        })
        @Explain(
                "The config files are 0600 root and steward-ui does not run as root, so this service reads and writes them.")
        default String configsRoot() {
            return "/configs";
        }
    }

    /** How this container asks steward-deployer to recreate one service. */
    @ConfigSpec
    interface DeployerSpec {

        @Order(1)
        @Name("URL")
        @Key("url")
        @Comment("Where steward-deployer's HTTP API answers from inside this container.")
        @NoExplanationNeeded
        default String url() {
            return "http://steward-deployer:8081";
        }

        @Order(2)
        @Name("Token")
        @Key("token")
        @Comment({
            "The shared secret sent as X-Steward-Token to ask steward-deployer for a recreate,",
            "the same value steward-ui sends. Empty means this container never asks. Set",
            "NORDTAL_STEWARD_DEPLOYER_TOKEN instead."
        })
        @Secret
        @Explain(
                "The same secret steward-ui already sends to steward-deployer. Empty means this container never asks for a recreate.")
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
        @Comment("HH:mm in this container's time zone, or empty for no scheduled update at all.")
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
