package eu.nordtal.s2.steward.worker.config;

import eu.nordtal.s2.steward.worker.plan.Topology;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.Secret;

import java.util.List;

/**
 * {@code config/steward.yml} - where every version comes from, and where the files it compares
 * against live.
 *
 * <p>Unlike the other configs in season 2, everything here has a real default: the repositories and
 * the Modrinth project ids are facts about <em>this</em> project rather than about a deployment, and
 * a worker that cannot start until somebody retypes {@code nordtal/season-2} is one that will be
 * started with a typo in it. {@link #githubToken()} is the one optional value.</p>
 *
 * <p>Platform versions are deliberately <em>not</em> here: they live in
 * {@link eu.nordtal.s2.common.Platform}, because a fact nothing should be able to override does not
 * belong in a file the environment wins over.</p>
 *
 * <p><b>Which release to follow is not a setting.</b> Both repositories are read through GitHub's
 * {@code /releases/latest}, which skips drafts and pre-releases by GitHub's own definition. The
 * {@code season-release} and {@code display-tags-release} keys were removed on 2026-09-09: a key
 * that pins a tag is a version number kept outside {@code gradle.properties}, and every version
 * number this project wrote down twice went stale. A bad release is corrected by publishing a
 * better one. The cost, stated plainly: there is no way back off a bad release except forward.</p>
 *
 * <p>Every setting is overridable by {@code NORDTAL_STEWARD_<PATH>} with {@code -} becoming
 * {@code _}; the environment wins over the file and is never written back to it.</p>
 */
@ConfigSpec(header = {
        "-------------------------------------------------------------------",
        "  steward-worker - where the versions come from",
        "-------------------------------------------------------------------",
        "This module resolves the newest version of everything the network",
        "runs, compares it against the jars actually lying in the volumes,",
        "and reports the difference. Nothing here decides WHEN that happens:",
        "steward-worker never updates on its own, only when it is asked.",
        "",
        "The defaults are the real values for nordtal.eu and are meant to be",
        "left alone. Change them to point a test deployment somewhere else.",
        "There is nothing here that pins a version: the newest release wins.",
        "",
        "Every setting can be overridden with an environment variable named",
        "NORDTAL_STEWARD_<PATH>, with '-' becoming '_':",
        "",
        "  season-repo   ->  NORDTAL_STEWARD_SEASON_REPO",
        "  volumes-root  ->  NORDTAL_STEWARD_VOLUMES_ROOT",
        "",
        "The environment wins over this file and is never written back to it."
})
public interface StewardSpec {

    @Order(1)
    @Name("Season repository")
    @Key("season-repo")
    @Comment({
            "The GitHub repository the five season 2 jars and the resource pack come from,",
            "as owner/name. Its releases are the only place those artefacts exist - nothing",
            "in this project publishes them anywhere else.",
            "",
            "WHICH release is not a setting and cannot be pinned: the newest published one",
            "wins, always. /releases/latest skips drafts and pre-releases, so an update that",
            "'did not arrive' is usually a release nobody pressed Publish on."
    })
    @Explain("The one place season 2's jars and the resource pack come from - always the newest published release, never a pinned one. Change it only to point a test deployment elsewhere.")
    default String seasonRepo() {
        return "nordtal/season-2";
    }

    @Order(2)
    @Name("Display tags repository")
    @Key("display-tags-repo")
    @Comment({
            "Our fork of the Text Display nametag plugin. Required on the SMP server:",
            "smp/src/main/resources/paper-plugin.yml declares it load: BEFORE, required: true,",
            "so the SMP plugin does not enable without it."
    })
    @Explain("Required on the SMP server - paper-plugin.yml declares it load:BEFORE, required:true, so smp refuses to enable without a release fetched from here.")
    default String displayTagsRepo() {
        return "nordtal/papermc-display-tags";
    }

    @Order(3)
    @Name("PacketEvents project")
    @Key("packetevents-project")
    @Comment({
            "The Modrinth project id of PacketEvents - the packet library DisplayTags is built",
            "on, and therefore required under it.",
            "",
            "THE ID, NOT THE SLUG. Both work in the API and the slug ('packetevents') is the",
            "readable one, but a slug is renameable by its author and an id is not. A rename",
            "would turn this into a 404 on the morning of a release."
    })
    @Explain("The Modrinth project ID, not the slug - a slug is renameable by its author and would turn into a 404 on the morning of a release.")
    default String packetEventsProject() {
        return "HYKaKraK";
    }

    // There is deliberately no minecraft-version, velocity-version, paper-build or velocity-build
    // key here. The two versions are eu.nordtal.s2.common.Platform: a platform version is a property
    // of the season - every plugin is compiled against one Paper API and the pack_format matches it -
    // so it must not be settable from an environment that wins over the source tree. The two build
    // pins have no replacement on purpose; do not reintroduce one, because an emergency brake nobody
    // has ever exercised is worse than none.

    @Order(5)
    @Name("Simple Voice Chat project")
    @Key("voicechat-project")
    @Comment({
            "The Modrinth project id of Simple Voice Chat ('simple-voice-chat').",
            "",
            "THE ID, NOT THE SLUG, for the reason packetevents-project gives.",
            "",
            "ONE ID, TWO ARTEFACTS. The project publishes a server half and a proxy half, and the",
            "worker resolves both from this one id - the `paper` build for smp and hunger-games",
            "(voicechat-bukkit-<version>.jar) and the `velocity` build for the proxy",
            "(voicechat-velocity-<version>.jar). The loader is what tells them apart.",
            "",
            "The proxy half is what makes ONE published UDP port enough for the whole network: it",
            "detects each backend's voice address and port itself, so no backend publishes a port",
            "and no voice_host has to be edited by hand. Without it every backend would need its",
            "own port open to the internet.",
            "",
            "It is also the one artefact the worker installs from a PRE-RELEASE. That is not a",
            "setting and cannot be turned on for anything else - see Modrinth.PRE_RELEASE_",
            "EXCEPTIONS, which names it and says why: the project has never published a Velocity",
            "build marked `release`, so waiting for one means never installing it at all."
    })
    @Explain("One Modrinth id resolves both the server and proxy voice builds, and is the one artefact this worker installs from a pre-release on purpose - the project has never published a Velocity build marked release.")
    default String voiceChatProject() {
        return "9eGKb6K1";
    }

    @Order(6)
    @Name("CoreProtect project")
    @Key("coreprotect-project")
    @Comment({
            "The Modrinth project id of CoreProtect ('coreprotect'), the block logger, on smp.",
            "The jar is CoreProtect-CE-<version>.jar.",
            "",
            "THERE IS NO BUILD FOR THIS MINECRAFT VERSION and the row is here anyway (checked",
            "2026-09-08: 24.0 is the newest release and stops at 26.1.2). It resolves as",
            "UNSUPPORTED, which is neither work nor a failure - nothing is installed, nothing is",
            "skipped, and smp's other plugins are not held back for it. The day a compatible",
            "release appears, the next `/update now` installs it and nobody edits any code.",
            "",
            "Blanking this key does NOT retire the artefact - it makes every run report the",
            "project id '' as unresolvable. Retiring it is an edit to Topology.SERVICES."
    })
    @Explain("Currently has no build for this Minecraft version and resolves UNSUPPORTED rather than failing. Blanking this does not retire the artefact, only makes it unresolvable - retiring it means editing Topology.SERVICES instead.")
    default String coreProtectProject() {
        return "Lu3KuzdV";
    }

    @Order(7)
    @Name("Volumes root")
    @Key("volumes-root")
    @Comment({
            "Where the four Minecraft volumes are mounted inside this container - one",
            "directory per compose service, named exactly as the service is:",
            "",
            "  <volumes-root>/proxy    <volumes-root>/limbo",
            "  <volumes-root>/hunger-games       <volumes-root>/smp",
            "",
            "A directory that is not there is reported as such rather than being created. This",
            "module refuses to invent a server that was not mounted: a worker that silently",
            "reports 'nothing installed' for a running SMP is worse than one that says the",
            "mount is missing."
    })
    @Explain("A directory that is not mounted here is reported missing rather than invented - a worker that silently says 'nothing installed' for a running server is worse than one that names the missing mount.")
    default String volumesRoot() {
        return "/volumes";
    }

    @Order(8)
    @Name("GitHub token")
    @Key("github-token")
    @Comment({
            "Optional. A token raises GitHub's unauthenticated rate limit of 60 requests per",
            "hour per IP; a run makes two GitHub calls, so the limit only matters on a host",
            "that shares its address with something busier.",
            "",
            "A fine-grained token with public read access is enough - this module only ever",
            "reads public releases and never writes to GitHub."
    })
    @NoExplanationNeeded
    default String githubToken() {
        return "";
    }

    @Order(9)
    @Name("HTTP timeout (seconds)")
    @Key("http-timeout-seconds")
    @Comment({
            "How long any single API call may take before the run gives up.",
            "",
            "A resolve that hangs is worse than one that fails: the report is what an operator",
            "waits for before pressing the restart button, so it has to arrive or say why not."
    })
    @Explain("How long any single API call may wait before the run gives up - a resolve that hangs is worse than one that fails, since an operator is waiting on the report.")
    default int httpTimeoutSeconds() {
        return 30;
    }

    @Order(10)
    @Name("Download timeout (seconds)")
    @Key("download-timeout-seconds")
    @Comment({
            "How long a single jar may take to download during `steward-worker apply`.",
            "",
            "Much larger than http-timeout-seconds and for a different reason: that one bounds six",
            "small JSON documents, this one bounds a Paper server jar of about 65 MB. Ten minutes",
            "is what deploy/minecraft/entrypoint.sh already allows itself for the same file."
    })
    @Explain("Much larger than http-timeout-seconds on purpose - this bounds downloading a ~65 MB Paper jar, not a handful of small JSON calls.")
    default int downloadTimeoutSeconds() {
        return 600;
    }

    @Order(11)
    @Name("Poll interval (seconds)")
    @Key("poll-interval-seconds")
    @Comment({
            "How often `steward-worker serve` looks in update_request for work it was not told about.",
            "",
            "THIS POLL - not the LISTEN/NOTIFY path - is the guarantee, exactly as it is for the",
            "season phase (proxy's gate.yml says the same thing about the same trade).",
            "Notifications are lost while a process is disconnected, so a listener that missed one",
            "must still find the work; the listener only makes a request feel instant.",
            "",
            "Fifteen seconds rather than the phase model's thirty, because a person is watching:",
            "an admin who pressed a button in Discord is looking at a spinner until this fires.",
            "The wait is also shortened automatically when a restart's countdown ends sooner - a",
            "countdown that reaches zero and then waits another fifteen seconds is a bug, not a",
            "tuning question."
    })
    @Explain("This poll - not the LISTEN/NOTIFY path - is the actual guarantee an update starts. Shorter than the phase model's 30s because an admin is watching a spinner in Discord.")
    default int pollIntervalSeconds() {
        return 15;
    }

    @Order(12)
    @Name("Bootstrap")
    @Key("bootstrap")
    @Comment({
            "Whether `steward-worker serve` installs what is MISSING before it reports itself ready.",
            "",
            "This is what makes a deployment possible with no shell on the host. A Minecraft",
            "server refuses to start on an empty plugins folder, so without this a brand new",
            "stack needs `docker compose run --rm steward-worker apply` typed by a person with a",
            "shell on the host. With it, the first start of this container fills every empty",
            "volume and only then touches the readiness marker everything else waits for.",
            "",
            "IT CANNOT MOVE A VERSION, and that is the point rather than a limitation. Only",
            "artefacts with NOTHING installed are fetched; an artefact that already has a jar",
            "keeps it, however old. So a crash restart at three in the morning finds nothing",
            "missing and does nothing at all, which is this module's first rule and stays true",
            "with this switched on. Upgrades remain a request somebody makes, from Discord or",
            "in game.",
            "",
            "Turn it off for a deployment where the volumes are filled some other way, or to",
            "make this container come up fast while something upstream is broken. The servers",
            "will then refuse to start until an apply has run, and say so by name."
    })
    @Explain("Whether serve installs missing artefacts before reporting ready. It can only fill an empty volume, never move an existing jar to a newer version - turn it off only if the volumes are filled some other way.")
    default boolean bootstrap() {
        return true;
    }

    @Order(13)
    @Name("bunq")
    @Key("bunq")
    @Comment({
            "The bank. This container is the only one in the network that holds a bunq credential",
            "and the only one that makes a call to bunq (steward/109); the Discord bot asks for a",
            "payment link by writing a row and reads back what happened.",
            "",
            "ALL OF IT IS OPTIONAL, both halves of the key together. A season without a bunq",
            "account is a season where everything works except buying access."
    })
    @Explain("The bunq account payments arrive in. This is the only process in the network that holds the key; leave it empty for a season that sells nothing.")
    BunqSpec bunq();

    @Order(15)
    @Name("Docker")
    @Key("docker")
    @Comment({
            "The daemon this service reads: container state, health, image drift, logs, the",
            "console, and the numbers behind the curves on the start page.",
            "",
            "READ, STOP AND START - AND NOTHING ELSE. Creating a container needs the compose file,",
            "which steward-deployer owns (§8b), and a container rebuilt from an inspect would drift",
            "from that file silently. DockerOps refuses it rather than improvising one.",
            "",
            "WITHOUT THE SOCKET MOUNTED NOTHING HERE FAILS: the drift check and the metrics say",
            "they could not look, which is a different answer from `everything is current` - and",
            "confusing those two is what let four releases run behind unnoticed."
    })
    @Explain("Read, stop and start only - never creates a container, since that belongs to steward-deployer's own compose file. Without the socket mounted this reports 'could not look' rather than silently claiming everything is current.")
    DockerSpec docker();

    @Order(14)
    @Name("Backup")
    @Key("backup")
    @Comment({
            "The nightly volume backup: which volumes are saved and which services are stopped",
            "while they are.",
            "",
            "STEWARD-WORKER DOES NOT SCHEDULE THIS AND MUST NOT. `serve` has exactly one rule it is",
            "protected by - it does nothing at all until a row appears in update_request - and a",
            "timer here would be the end of it. That held until 2026-09-13, when the clock moved",
            "here - see backup.at, which says what was kept of the rule and what was given up."
    })
    @Explain("steward-worker does not schedule this itself - the nightly row is written by smp's own daily clock, so a season with smp down gets no nightly backup and nothing else notices.")
    BackupSpec backup();

    @Order(18)
    @Name("Update schedule")
    @Key("update")
    @Comment({
            "An optional clock that asks for a whole-network UPDATE on the days and at the time",
            "below. Off by default: with update.at empty an update only ever starts when an admin",
            "asks for one, which is what every deployment before this key did.",
            "",
            "Like backup.at it writes a request row and nothing else. The run it asks for is the",
            "same one the Update button asks for, countdown, cancel and one-run-at-a-time included.",
            "",
            "Saving this file through Steward re-arms both clocks at once; no restart is needed."
    })
    @Explain("Off unless update.at is set. When set, a whole-network update is asked for on the chosen days, with the same countdown a manual one gets.")
    UpdateSpec update();

    @Order(17)
    @Name("Deployer")
    @Key("deployer")
    @Comment({
            "steward-deployer, the one process in this deployment allowed to create a container",
            "(season-2-ops/22, deploy/README.md §8b).",
            "",
            "THE `docker` BLOCK ABOVE STILL REFUSES TO RECREATE A CONTAINER ON ITS OWN, and that",
            "does not change here: a container rebuilt from an inspect would drift from",
            "compose.yml silently, and only steward-deployer carries that file. What this key",
            "wires is the request ACROSS that boundary - when an update finds a service's image",
            "out of date, it asks steward-deployer's HTTP API to pull it and run",
            "`compose up --force-recreate --no-deps <service>`, instead of leaving the service",
            "on its old image until somebody types that command on the host by hand.",
            "",
            "Without a token below this asks nothing, on purpose - see token."
    })
    @Explain("Wires the request for a container recreate across the boundary that keeps container creation to steward-deployer alone. Without a token below, this asks nothing at all rather than half-recreating a service.")
    DeployerSpec deployer();

    @Order(16)
    @Name("API")
    @Key("api")
    @Comment({
            "The internal API steward-ui reads this container through.",
            "",
            "WHY IT EXISTS: §3 keeps the docker socket away from the web interface, so everything",
            "the interface knows about a container arrives over this. What it offers is a list, a",
            "log, a search and one console line - stopping and starting are NOT here. Those are a",
            "row in update_request, which is countable, cancellable and counted down in front of",
            "every player online.",
            "",
            "IT DOES NOT SERVE WITHOUT A TOKEN. A console anybody on the network can type into is",
            "a remote shell with a nicer font. The secret lives in the host's .env and compose",
            "hands the same one to both containers."
    })
    @Explain("Keeps the Docker socket away from steward-ui - offers a list, a log, a search and one console line, never stop or start. Without a token below it refuses to serve at all.")
    ApiSpec api();

    /**
     * bunq: the credentials, where the API context file lives, and the poll that asks the bank what
     * has arrived.
     *
     * <p>Moved here from {@code discord-bot}'s {@code BotSpec.BunqSpec} and
     * {@code AccessSpec.PaymentSpec} on 2026-09-18 (steward/109). What came with it is everything
     * that is a question about <em>bunq</em>; what stayed in {@code access.yml} is everything that is
     * a question about a <em>purchase</em> - {@code request-ttl-hours} in particular, because the bot
     * is what writes the row and stamps its expiry, and the sentence "this link is valid for N hours"
     * is printed by the same process out of the same value.</p>
     */
    @ConfigSpec
    interface BunqSpec {

        @Order(1)
        @Name("API key")
        @Key("api-key")
        @Comment({
                "bunq API key. Set NORDTAL_STEWARD_BUNQ_API_KEY instead of filling this in.",
                "",
                "IT WAS NORDTAL_BOT_BUNQ_API_KEY until 2026-09-18 and is read by a different",
                "container now. An environment file still carrying the old name leaves this empty,",
                "which is a VALID state - so the stack comes up healthy and silently never notices",
                "a payment again. That is why this container says which of the two it is, in one",
                "line, on every start."
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
                "The bunq monetary account id that is polled and billed.",
                "A number. The worker will not start if it is set and not numeric."
        })
        @Explain("A number, not an IBAN or alias - the worker refuses to start if it is non-numeric, or if only one half of the key pair is filled in.")
        default String accountId() {
            return "";
        }

        // A PRODUCTION/SANDBOX `environment` key deliberately does not exist, and did not exist in
        // BotSpec either: there is no sandbox key, so it would be a switch on the one code path
        // that moves other people's money whose only remaining use is to be set wrongly. Do not
        // reintroduce it without a sandbox key.

        @Order(3)
        @Name("Context path")
        @Key("context-path")
        @Comment({
                "Where the bunq API context file is kept. It holds credentials and lives in a",
                "Docker-managed volume, never on the host filesystem.",
                "Empty means the working directory.",
                "",
                "ITS CONTENTS ARE NEVER COPIED BETWEEN MACHINES OR CONTAINERS. bunq binds a",
                "context to the device and address it was registered from, so a context carried",
                "over from somewhere else is refused by the bank rather than reused. A fresh",
                "container with an empty volume registers a new one on its first call."
        })
        @Explain("Where the bunq API context file (holds credentials) is kept. Never copy one in from elsewhere - bunq binds it to the device it was registered from.")
        default String contextPath() {
            return "";
        }

        @Order(4)
        @Name("Poll interval (seconds)")
        @Key("poll-interval-seconds")
        @Comment({
                "How often bunq is asked about open tabs and recent payments.",
                "",
                "This is the poll that COSTS SOMETHING - it is HTTP to a bank - which is why it is",
                "its own number and not the seam's. The bot's side of the seam re-reads the",
                "database on access.yml's payment.poll-interval-seconds, and both are woken early",
                "by nordtal_payment; the polls are the guarantee underneath that."
        })
        @Explain("How often the bank itself is asked. This is an HTTP call to bunq, unlike the bot's own poll of the same purchase - the two are separate numbers on purpose.")
        default int pollIntervalSeconds() {
            return 30;
        }

        @Order(5)
        @Name("Watermark")
        @Key("watermark")
        @Comment({
                "Payments created before this instant are ignored, completely and forever.",
                "",
                "LEAVE THIS EMPTY. On its first start the process stamps the current instant into",
                "the database and uses that from then on, so the cut-off is the moment this",
                "deployment first ran rather than a date somebody guessed. The stored value is",
                "written once and never rewritten - including across this setting moving here from",
                "access.yml, because it is the same row in the same table.",
                "",
                "Set it only to deliberately choose a different cut-off; ISO-8601, UTC, e.g.",
                "2026-09-01T00:00:00Z. A value here overrides the stored one without replacing it,",
                "so emptying this again falls back to the original first-start instant.",
                "",
                "The cut-off is not an optimisation: bunq returns the last 50 payments on the",
                "account whatever the database knows, so without one the first poll would book up",
                "to 50 historical payments - grants, roles, DMs and public thank-yous included."
        })
        @Explain("Leave empty - the first start stamps this itself. Setting it manually risks booking historical payments the wrong side of the cut-off.")
        default String watermark() {
            return "";
        }

        @Order(6)
        @Name("Recent payment count")
        @Key("recent-payment-count")
        @Comment({
                "How many recent payments the fallback reference scan looks at per poll.",
                "The primary match path is the tab's own result inquiries; this only catches",
                "money that reached the account outside a tab."
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
                "The unix socket of the Docker daemon, as this container sees it.",
                "",
                "The default is where it is on every Linux host and where compose.yml mounts it.",
                "A path that is not there is not an error at startup: it is reported once, and",
                "everything that needs the daemon then answers `could not look`."
        })
        @Explain("Missing here is not a startup failure - it is reported once, and everything needing the daemon then answers 'could not look' rather than claiming everything is fine.")
        default String socket() {
            return "/var/run/docker.sock";
        }

        @Order(2)
        @Name("Compose project")
        @Key("project")
        @Comment({
                "The compose project name. Containers are <project>-<service>-1, and this is what",
                "separates ours from anything else running on the same daemon.",
                "",
                "It is written down rather than guessed from this container's own labels: guessing",
                "works until somebody runs a second copy of the stack under another name, and then",
                "it works differently instead of failing."
        })
        @Explain("Written down rather than guessed from this container's own labels - guessing works until a second copy of the stack runs under another name, and then it works differently instead of failing.")
        default String project() {
            return "nordtal-s2";
        }

        @Order(3)
        @Name("Metrics")
        @Key("metrics")
        @Comment({
                "Whether the 30-second sampler runs (§10c). Eleven series, ~32 000 rows a day,",
                "about 60 MB after 30 days, then compacted to hourly means - a thirtieth of that,",
                "with the year still in it.",
                "",
                "THE TABLE IS IN THE BACKUP. Turning this off is therefore also a way to make every",
                "night's snapshot smaller, and the cost is that the start page has no curves."
        })
        @Explain("The metrics table rides inside the nightly database backup - turning this off also shrinks every night's snapshot, at the cost of the start page's curves.")
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
        @Comment({
                "The port inside the container. It is published to nothing: compose puts this",
                "service and steward-ui on the same network, and the interface is the only thing",
                "that ever calls it."
        })
        @NoExplanationNeeded
        default int port() {
            return 8082;
        }

        @Order(2)
        @Name("Token")
        @Key("token")
        @Comment({
                "The shared secret steward-ui sends as X-Steward-Token. Empty means the API does",
                "not start at all - and the rest of this service carries on, because an update run",
                "does not need it.",
                "",
                "It comes from the environment in a deployment (NORDTAL_STEWARD_API_TOKEN), so",
                "this file holds an empty string rather than a secret somebody might commit."
        })
        @Secret
        @Explain("Empty disables just this internal API - the rest of the service, including update runs, keeps working without it. Comes from the environment in a real deployment, never committed here.")
        default String token() {
            return "";
        }

        @Order(3)
        @Name("Configs root")
        @Key("configs-root")
        @Comment({
                "Where every service's configuration is mounted in THIS container, one directory",
                "per compose service, named after it. steward-ui draws the form; this service",
                "reads and writes the files.",
                "",
                "IT MOVED HERE ON 2026-09-14, and the reason is a measurement rather than a",
                "preference. Every config jcore writes is 0600 root:root, and steward-ui is the",
                "one service in the stack that does not run as root - so it could neither read",
                "nor write any of them, and tapping a file answered `HTTP 400 Permission denied`.",
                "Making the files readable was the wrong repair: database.yml holds the Postgres",
                "password and bot.yml the Discord token. So the mounts left the web layer, which",
                "is what §3 already says about the docker socket.",
                "",
                "Everything under it ending in .yml is offered. Nothing outside it can be reached:",
                "a file is found by matching what the browser asked against the list actually",
                "discovered, never by joining a path onto this one."
        })
        @Explain("Moved here from steward-ui's own mount on 2026-09-14: every jcore-written config file is 0600 root:root and steward-ui does not run as root, so it could neither read nor write them directly.")
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
        @Comment({
                "Where steward-deployer's HTTP API answers, from inside this container.",
                "",
                "The default is the compose service name and the port StewardDeployer listens",
                "on by default - both fixed by this deployment's own compose.yml rather than by",
                "an operator, so there is normally nothing to change here."
        })
        @NoExplanationNeeded
        default String url() {
            return "http://steward-deployer:8081";
        }

        @Order(2)
        @Name("Token")
        @Key("token")
        @Comment({
                "The shared secret this container sends as X-Steward-Token when it asks",
                "steward-deployer to recreate a service. steward-ui and steward-deployer already",
                "share a secret for the same header (NORDTAL_STEWARD_UI_DEPLOYER_TOKEN) - this is",
                "a second reader of the same value, carried in the same environment variable",
                "compose.yml already requires (STEWARD_DEPLOYER_TOKEN), not a second secret to",
                "keep in step with it.",
                "",
                "Empty means this container does not ask at all: a recreate refuses exactly as",
                "it did before this key existed, named, and the service is left on the image it",
                "already had rather than half-recreated. It comes from the environment in a",
                "deployment (NORDTAL_STEWARD_DEPLOYER_TOKEN), so this file holds an empty string",
                "rather than a secret somebody might commit."
        })
        @Secret
        @Explain("Shares the same secret steward-ui already sends to steward-deployer, not a second one to keep in step. Empty means this container never asks for a recreate, leaving a stale service on its old image rather than half-recreating it.")
        default String token() {
            return "";
        }

        @Order(3)
        @Name("Timeout (seconds)")
        @Key("timeout-seconds")
        @Comment({
                "How long one recreate may take - steward-deployer pulling a new image plus",
                "`compose up --force-recreate --no-deps` - before this process stops waiting for",
                "its job to settle and reports it unfinished rather than failed.",
                "",
                "Longer than http-timeout-seconds on purpose: that one bounds a handful of small",
                "JSON documents and this one bounds a pull of a multi-hundred-MB image over the",
                "network this host is on. Ten minutes matches download-timeout-seconds, which",
                "bounds the same kind of wait on this container's own side of a jar download."
        })
        @Explain("Bounds a full image pull plus recreate, not a small API call - matches download-timeout-seconds, which bounds the same kind of wait on this container's own side.")
        default int timeoutSeconds() {
            return 600;
        }
    }

    /** What a {@code BACKUP} run saves and what it stops while it does. */
    @ConfigSpec
    interface BackupSpec {

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
                "are new on 2026-09-08 and hold the only hand-edited files in the deployment:",
                "every plugin's config.yml and smp's milestones.yml and sounds.yml.",
                "steward-ui-config joined them on",
                "2026-09-13 - it is where the interface's own settings live, and the one config",
                "volume the interface cannot rebuild for you.",
                "",
                "PROXY AND LIMBO LEFT THIS LIST ON 2026-09-20, data and plugins alike, and it was",
                "a decision rather than an omission (season-2-ops/137). Neither holds anything a",
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
        @Explain("The volumes' REAL names (docker volume ls, prefixed by the compose project), not the compose.yml keys. A name with no matching read-only mount below fails loudly rather than silently skipping.")
        default List<String> volumes() {
            return List.of("nordtal-s2_mc-smp",
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
                "proxy left this list with its volumes on 2026-09-20 (season-2-ops/137), and that",
                "is the whole point of that ticket: nothing the proxy holds is saved any more, so",
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
        @Explain("Compose service names taken from Topology rather than typed by hand - a stale literal here once left a service running through its own snapshot, when its compose name changed and this string did not.")
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
        @Explain("Read-only is enforced by the compose mount, not by this setting - a backup that could write to what it is saving is one bug away from being the thing that destroys it.")
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
        @Explain("Must never be one of the volumes listed above - a backup directory inside a backed-up volume grows by its own contents every night until the disk is gone.")
        default String outputRoot() {
            return "/backups";
        }

        @Order(5)
        @Name("Retention")
        @Key("retention")
        @Comment({
                "How long a backup is kept here. Till chose the staggered schedule on 2026-09-18",
                "(steward/95): the newest days in full, then one a week, then one a month.",
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
        @Explain("The staggered schedule: the newest days in full, then one a week, then one a month. It counts days rather than files, which a flat count could not.")
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
        @Explain("pg_dump runs INSIDE this service, so the dump can never be taken by an older client than the server. Empty turns off just the database dump - the volume archives are unaffected.")
        default String databaseService() {
            return "postgres";
        }

        @Order(7)
        @Name("Time of day")
        @Key("at")
        @Comment({
                "Local time of day the nightly backup is asked for, HH:mm. Empty means none.",
                "",
                "THE CLOCK MOVED HERE ON 2026-09-13, and it is a rule being rewritten rather than",
                "broken. `serve` used to have exactly one protection - it did nothing at all until",
                "a row appeared in update_request - so the nightly row was written by `smp`, which",
                "already ran a daily clock. The hole in that is what moved it: a season with `smp`",
                "down had no backup and nothing said so.",
                "",
                "What is kept is the part that mattered: this writes a request row and nothing",
                "else. It never claims one, never runs one, never touches a jar. Everything after",
                "the row is the same path /backup now takes, lock and countdown included.",
                "",
                "04:45 is what the SMP used, and it is kept: it is the quietest hour of this",
                "network's day. It was chosen because the farm world was reset shortly after and",
                "the reset refused to run without a recent backup behind it; the farm world went",
                "on 2026-09-20 (season-2-ingame/30) and the hour did not become a worse one.",
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
        @Explain("Which weekdays the nightly backup runs on. All seven by default. An empty list means no nightly backup at all.")
        default List<String> days() {
            return List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY",
                    "SUNDAY");
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
                "Thirty minutes rather than an hour (owner, 2026-09-09), because giving up is no",
                "longer silent: a run that ends FAILED mentions the admin role in the admin channel",
                "instead of only editing an embed nobody is looking at at five in the morning. That",
                "is what makes the shorter wait safe - the network comes back sooner and somebody",
                "is told that a volume was not saved. What this must not be is infinite."
        })
        @Explain("How long one volume's snapshot may run before this gives up and restarts the servers. The network stays down for the whole wait, so a FAILED result at the end pings the admin role rather than going unnoticed.")
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
                "script knows cannot be changed from the interface, and steward/95 made the backup",
                "page the one place the target is read and typed. steward/08 is the upload itself."
        })
        @Explain("Where a copy goes that is not on this disk. Empty endpoint means there is none, and every archive then lives on the same disk as the volume it is a copy of.")
        RemoteSpec remote();

        /**
         * The offsite target: an S3 bucket, and the two credentials for it.
         *
         * <p><b>The two keys are {@link eu.nordtal.jcore.config.spec.annotation.Secret}, and that is
         * what makes them typeable from a browser without being readable in one.</b>
         * {@code ConfigApi} sends a secret as {@code filled: true} and no value, so the backup page can
         * say that a key is set without the key itself ever being in a browser cache, a screen
         * recording or the next XSS - and a new one can still be typed over it, because typing does not
         * require having seen the old one. The leaf-key heuristic in {@code ConfigEntry} would catch
         * both of these names anyway; the annotation is there so the masking does not depend on what
         * the key happens to be called.</p>
         */
        /**
         * How long a backup is kept - the numbers; {@code Retention} in the backup package is the
         * arithmetic that reads them.
         *
         * <p>Four keys rather than one, because Till's rule is two rules: the staggered schedule
         * every backup tool has, and the one-per-day collapse that no standard tool does.</p>
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
            @Explain("Fourteen DAYS, not fourteen files - several runs on one day count as that one day. Below 1 the sweep refuses rather than deleting everything.")
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
            @Explain("Eight weeks of history, counted from this week - the first two of them are already covered by the daily window. 0 ends the history where the daily window ends.")
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
                    "survives - Till asked for \"a few days later\". A backup taken by hand before",
                    "touching something must not vanish the moment the nightly one lands, because",
                    "that is the one moment somebody is still working on what they took it for.",
                    "",
                    "NOTHING INSIDE THIS WINDOW IS EVER DELETED by the sweep, for any reason.",
                    "0 collapses a day as soon as the next sweep sees it."
            })
            @Explain("A backup taken by hand before touching something survives the nightly one for this many days. Nothing inside the window is ever deleted, for any reason.")
            default int collapseAfterDays() {
                return 3;
            }
        }

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
            @Explain("Empty means there is no offsite copy - every archive then lives on the same disk as the thing it is a copy of.")
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

    /** When a whole-network {@code UPDATE} is asked for without anybody pressing the button. */
    @ConfigSpec
    interface UpdateSpec {

        @Order(1)
        @Name("At")
        @Key("at")
        @Comment({
                "HH:mm in this container's time zone, or empty for no scheduled update at all.",
                "Empty is the default: nothing updates on a schedule unless somebody chose that."
        })
        @Explain("Empty means no scheduled update. An admin can always start one by hand.")
        default String at() {
            return "";
        }

        @Order(2)
        @Name("Days")
        @Key("days")
        @Comment({
                "Which weekdays the scheduled update runs on, read exactly like backup.days.",
                "Nothing happens on any of them while update.at is empty."
        })
        @Explain("Which weekdays the scheduled update runs on. Ignored while update.at is empty.")
        default List<String> days() {
            return List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY",
                    "SUNDAY");
        }
    }
}
