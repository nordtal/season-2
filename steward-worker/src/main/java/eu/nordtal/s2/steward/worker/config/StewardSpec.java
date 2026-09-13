package eu.nordtal.s2.steward.worker.config;

import eu.nordtal.s2.steward.worker.plan.Topology;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

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
    default String seasonRepo() {
        return "nordtal/season-2";
    }

    @Order(2)
    @Key("display-tags-repo")
    @Comment({
            "Our fork of the Text Display nametag plugin. Required on the SMP server:",
            "smp/src/main/resources/paper-plugin.yml declares it load: BEFORE, required: true,",
            "so the SMP plugin does not enable without it."
    })
    default String displayTagsRepo() {
        return "nordtal/papermc-display-tags";
    }

    @Order(3)
    @Key("packetevents-project")
    @Comment({
            "The Modrinth project id of PacketEvents - the packet library DisplayTags is built",
            "on, and therefore required under it.",
            "",
            "THE ID, NOT THE SLUG. Both work in the API and the slug ('packetevents') is the",
            "readable one, but a slug is renameable by its author and an id is not. A rename",
            "would turn this into a 404 on the morning of a release."
    })
    default String packetEventsProject() {
        return "HYKaKraK";
    }

    @Order(4)
    @Key("chunky-project")
    @Comment({
            "The Modrinth project id of Chunky, the chunk pre-generator ('chunky').",
            "",
            "Chunky is also a compileOnly dependency of :smp at a version pinned in",
            "gradle/libs.versions.toml. A version resolved here that is ahead of that pin is",
            "how you get a NoSuchMethodError in production and nowhere else, so an update to",
            "Chunky is a reason to look at the catalog - the report says so when it moves."
    })
    default String chunkyProject() {
        return "fALzjamp";
    }

    // There is deliberately no minecraft-version, velocity-version, paper-build or velocity-build
    // key here. The two versions are eu.nordtal.s2.common.Platform: a platform version is a property
    // of the season - every plugin is compiled against one Paper API and the pack_format matches it -
    // so it must not be settable from an environment that wins over the source tree. The two build
    // pins have no replacement on purpose; do not reintroduce one, because an emergency brake nobody
    // has ever exercised is worse than none.

    @Order(5)
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
    default String voiceChatProject() {
        return "9eGKb6K1";
    }

    @Order(6)
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
    default String coreProtectProject() {
        return "Lu3KuzdV";
    }

    @Order(7)
    @Key("volumes-root")
    @Comment({
            "Where the four Minecraft volumes are mounted inside this container - one",
            "directory per compose service, named exactly as the service is:",
            "",
            "  <volumes-root>/network-control    <volumes-root>/limbo",
            "  <volumes-root>/hunger-games       <volumes-root>/smp",
            "",
            "A directory that is not there is reported as such rather than being created. This",
            "module refuses to invent a server that was not mounted: a worker that silently",
            "reports 'nothing installed' for a running SMP is worse than one that says the",
            "mount is missing."
    })
    default String volumesRoot() {
        return "/volumes";
    }

    @Order(8)
    @Key("github-token")
    @Comment({
            "Optional. A token raises GitHub's unauthenticated rate limit of 60 requests per",
            "hour per IP; a run makes two GitHub calls, so the limit only matters on a host",
            "that shares its address with something busier.",
            "",
            "A fine-grained token with public read access is enough - this module only ever",
            "reads public releases and never writes to GitHub."
    })
    default String githubToken() {
        return "";
    }

    @Order(9)
    @Key("http-timeout-seconds")
    @Comment({
            "How long any single API call may take before the run gives up.",
            "",
            "A resolve that hangs is worse than one that fails: the report is what an operator",
            "waits for before pressing the restart button, so it has to arrive or say why not."
    })
    default int httpTimeoutSeconds() {
        return 30;
    }

    @Order(10)
    @Key("download-timeout-seconds")
    @Comment({
            "How long a single jar may take to download during `steward-worker apply`.",
            "",
            "Much larger than http-timeout-seconds and for a different reason: that one bounds six",
            "small JSON documents, this one bounds a Paper server jar of about 65 MB. Ten minutes",
            "is what deploy/minecraft/entrypoint.sh already allows itself for the same file."
    })
    default int downloadTimeoutSeconds() {
        return 600;
    }

    @Order(11)
    @Key("poll-interval-seconds")
    @Comment({
            "How often `steward-worker serve` looks in update_request for work it was not told about.",
            "",
            "THIS POLL - not the LISTEN/NOTIFY path - is the guarantee, exactly as it is for the",
            "season phase (network-control's gate.yml says the same thing about the same trade).",
            "Notifications are lost while a process is disconnected, so a listener that missed one",
            "must still find the work; the listener only makes a request feel instant.",
            "",
            "Fifteen seconds rather than the phase model's thirty, because a person is watching:",
            "an admin who pressed a button in Discord is looking at a spinner until this fires.",
            "The wait is also shortened automatically when a restart's countdown ends sooner - a",
            "countdown that reaches zero and then waits another fifteen seconds is a bug, not a",
            "tuning question."
    })
    default int pollIntervalSeconds() {
        return 15;
    }

    @Order(12)
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
    default boolean bootstrap() {
        return true;
    }

    @Order(15)
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
            "confusing those two is what let four releases run behind unnoticed (todo.md A24)."
    })
    DockerSpec docker();

    @Order(14)
    @Key("backup")
    @Comment({
            "The nightly volume backup: which volumes are saved and which services are stopped",
            "while they are.",
            "",
            "STEWARD-WORKER DOES NOT SCHEDULE THIS AND MUST NOT. `serve` has exactly one rule it is",
            "protected by - it does nothing at all until a row appears in update_request - and a",
            "timer here would be the end of it. The nightly row is written by `smp`, which already",
            "owns a daily clock for the farm world; see smp's config.yml#backup-time. An admin",
            "asks for one with /backup now. The consequence is written down rather than hidden: a",
            "season with `smp` down has no nightly backup and nothing else notices."
    })
    BackupSpec backup();

    @Order(16)
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
    ApiSpec api();

    /** Where the daemon is, and which compose project is ours. */
    @ConfigSpec
    interface DockerSpec {

        @Order(1)
        @Key("socket")
        @Comment({
                "The unix socket of the Docker daemon, as this container sees it.",
                "",
                "The default is where it is on every Linux host and where compose.yml mounts it.",
                "A path that is not there is not an error at startup: it is reported once, and",
                "everything that needs the daemon then answers `could not look`."
        })
        default String socket() {
            return "/var/run/docker.sock";
        }

        @Order(2)
        @Key("project")
        @Comment({
                "The compose project name. Containers are <project>-<service>-1, and this is what",
                "separates ours from anything else running on the same daemon.",
                "",
                "It is written down rather than guessed from this container's own labels: guessing",
                "works until somebody runs a second copy of the stack under another name, and then",
                "it works differently instead of failing."
        })
        default String project() {
            return "nordtal-s2";
        }

        @Order(3)
        @Key("metrics")
        @Comment({
                "Whether the 30-second sampler runs (§10c). Eleven series, ~32 000 rows a day,",
                "about 60 MB after 30 days, then compacted to hourly means - a thirtieth of that,",
                "with the year still in it.",
                "",
                "THE TABLE IS IN THE BACKUP. Turning this off is therefore also a way to make every",
                "night's snapshot smaller, and the cost is that the start page has no curves."
        })
        default boolean metrics() {
            return true;
        }
    }

    /** The port and the shared secret of the internal API. */
    @ConfigSpec
    interface ApiSpec {

        @Order(1)
        @Key("port")
        @Comment({
                "The port inside the container. It is published to nothing: compose puts this",
                "service and steward-ui on the same network, and the interface is the only thing",
                "that ever calls it."
        })
        default int port() {
            return 8082;
        }

        @Order(2)
        @Key("token")
        @Comment({
                "The shared secret steward-ui sends as X-Steward-Token. Empty means the API does",
                "not start at all - and the rest of this service carries on, because an update run",
                "does not need it.",
                "",
                "It comes from the environment in a deployment (NORDTAL_STEWARD_API_TOKEN), so",
                "this file holds an empty string rather than a secret somebody might commit."
        })
        default String token() {
            return "";
        }
    }

    /** What a {@code BACKUP} run saves and what it stops while it does. */
    @ConfigSpec
    interface BackupSpec {

        @Order(1)
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
                "mc-network-control carries velocity.toml and the forwarding secret. bot-config",
                "is the bot's. The four *-plugins volumes",
                "are new on 2026-09-08 and hold the only hand-edited files in the deployment:",
                "every plugin's config.yml, smp's milestones.yml and sounds.yml, and the proxy's",
                "pack.yml with the resource pack's SHA-1 in it. steward-ui-config joined them on",
                "2026-09-13 - it is where the interface's own settings live, and the one config",
                "volume the interface cannot rebuild for you.",
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
                "mc-limbo and mc-hunger-games are absent too - limbo builds its",
                "world at every enable and the hunger games arena is a folder that is copied in,",
                "so both are rebuilt rather than restored. bot-jar and steward-worker-jar are refilled by",
                "`steward-worker bootstrap`.",
                "",
                "WHERE a snapshot goes is backup.output-root, on this host. There is no offsite",
                "copy yet: §9a's Storage Box does not exist, so every archive is on the same disk",
                "as the thing it is a copy of, and backup.keep of them protect against a mistake",
                "and against nothing else. todo.md A29 is where that is being chased."
        })
        default List<String> volumes() {
            return List.of("nordtal-s2_mc-smp",
                    "nordtal-s2_mc-smp-plugins",
                    "nordtal-s2_mc-network-control",
                    "nordtal-s2_mc-network-control-plugins",
                    "nordtal-s2_mc-limbo-plugins",
                    "nordtal-s2_mc-hunger-games-plugins",
                    "nordtal-s2_bot-config",
                    "nordtal-s2_steward-ui-config");
        }

        @Order(2)
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
                "limbo and hunger-games are absent: neither holds a world worth saving, and an",
                "outage with nothing to show for it is worse than no backup. Their plugins/",
                "volumes are still snapshotted - a config.yml is written at enable and at reload",
                "and at no other time, so there is nothing in flight to tear.",
                "",
                "THESE ARE COMPOSE SERVICE NAMES AND ARE THEREFORE TAKEN FROM Topology RATHER THAN",
                "TYPED. A literal here was `bot` while compose.yml's service became `discord-bot`,",
                "and a name no container carries is a service that is never stopped - which is a",
                "snapshot of a running server, i.e. the exact thing this list exists to prevent."
        })
        default List<String> stopServices() {
            return List.of(Topology.SMP, Topology.NETWORK_CONTROL, Topology.DISCORD_BOT);
        }

        @Order(3)
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
        default String sourcesRoot() {
            return "/backup-sources";
        }

        @Order(4)
        @Key("output-root")
        @Comment({
                "Where the archives and the database dump are written. Its own volume, and NOT one",
                "of the volumes being saved - a backup directory inside a backed-up volume grows by",
                "its own contents every night until the disk is gone.",
                "",
                "This is also what the Storage Box upload reads, so everything worth shipping",
                "offsite is in one directory by construction."
        })
        default String outputRoot() {
            return "/backups";
        }

        @Order(5)
        @Key("keep")
        @Comment({
                "How many archives of each volume, and how many database dumps, are kept here.",
                "Fourteen is what the postgres-backup sidecar kept and there is no reason to",
                "disagree with it.",
                "",
                "LOCAL RETENTION IS NOT THE OFFSITE ONE. This number governs the disk in this host",
                "only. Until a Storage Box exists, it is the ONLY retention there is - and then",
                "fourteen copies on the same disk as the original protect against a mistake and",
                "against nothing else."
        })
        default int keep() {
            return 14;
        }

        @Order(6)
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
        default String databaseService() {
            return "postgres";
        }

        @Order(7)
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
                "04:45 is what the SMP used. The farm world is reset shortly after, and since the",
                "same day `smp` refuses to reset a world that has no recent successful backup",
                "behind it - so this time and that one are no longer a promise two config files",
                "make to each other.",
                "",
                "THE TIMEZONE IS THIS CONTAINER'S (compose sets TZ). The resolved zone and the next",
                "firing are logged on every start, because a backup that runs an hour off is a",
                "thing nobody notices until the clocks change."
        })
        default String at() {
            return "04:45";
        }

        @Order(8)
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
        default int patienceMinutes() {
            return 30;
        }
    }
}
