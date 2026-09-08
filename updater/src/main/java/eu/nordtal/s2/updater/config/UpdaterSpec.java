package eu.nordtal.s2.updater.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

import java.util.List;

/**
 * {@code config/updater.yml} - where every version comes from, and where the files it compares
 * against live.
 *
 * <h2>Everything here has a real default, and that is not this repository's habit</h2>
 * Every other config in season 2 leaves ids, tokens and URLs empty and refuses to start, because
 * a guessed id is somebody else's guild. This file is the opposite on purpose: the repositories,
 * the two Modrinth project ids and the platform versions are facts about <em>this</em> project,
 * not about a deployment, and an updater that cannot start until an operator retypes
 * {@code nordtal/season-2} is an updater that will be started with a typo in it.
 *
 * <p>The one value that behaves the usual way is {@link #githubToken()}: empty, optional, and
 * only there for the rate limit.</p>
 *
 * <h2>What "latest" means, and why pinning is the rollback</h2>
 * {@link #seasonRelease()} and {@link #displayTagsRelease()} take either the word {@code latest}
 * or an exact tag. {@code latest} asks GitHub's {@code /releases/latest}, which by GitHub's own
 * definition skips drafts and pre-releases. An exact tag is how a run is turned into a rollback -
 * docs/updater.md#what-it-deliberately-does-not-do - and it is a person's decision, never this
 * module's.
 *
 * <h2>Every setting is overridable</h2>
 * The environment variable is the setting's path with {@code -} becoming {@code _}, under the
 * prefix {@code NORDTAL_UPDATER_}:
 *
 * <pre>
 *   season-release  -&gt;  NORDTAL_UPDATER_SEASON_RELEASE
 *   volumes-root    -&gt;  NORDTAL_UPDATER_VOLUMES_ROOT
 * </pre>
 *
 * The environment wins over the file and is never written back to it.
 */
@ConfigSpec(header = {
        "-------------------------------------------------------------------",
        "  updater - where the versions come from",
        "-------------------------------------------------------------------",
        "This module resolves the newest version of everything the network",
        "runs, compares it against the jars actually lying in the volumes,",
        "and reports the difference. Nothing here decides WHEN that happens:",
        "the updater never updates on its own, only when it is asked.",
        "",
        "The defaults are the real values for nordtal.eu and are meant to be",
        "left alone. Change them to point a test deployment somewhere else,",
        "or to pin a release for a rollback.",
        "",
        "Every setting can be overridden with an environment variable named",
        "NORDTAL_UPDATER_<PATH>, with '-' becoming '_':",
        "",
        "  season-release  ->  NORDTAL_UPDATER_SEASON_RELEASE",
        "  paper-build     ->  NORDTAL_UPDATER_PAPER_BUILD",
        "",
        "The environment wins over this file and is never written back to it."
})
public interface UpdaterSpec {

    @Order(1)
    @Key("season-repo")
    @Comment({
            "The GitHub repository the five season 2 jars and the resource pack come from,",
            "as owner/name. Its releases are the only place those artefacts exist - nothing",
            "in this project publishes them anywhere else."
    })
    default String seasonRepo() {
        return "nordtal/season-2";
    }

    @Order(2)
    @Key("season-release")
    @Comment({
            "Which release of season-repo to follow: the word 'latest', or an exact tag such",
            "as 'v0.2.0'.",
            "",
            "'latest' asks GitHub's /releases/latest, which SKIPS DRAFTS AND PRE-RELEASES by",
            "GitHub's own definition. That is the wanted behaviour and also the trap: a",
            "release left as a draft is invisible here, and the update that 'did not arrive'",
            "is a release nobody pressed Publish on.",
            "",
            "An exact tag is how a rollback is expressed. It is a person's decision and this",
            "module never writes it."
    })
    default String seasonRelease() {
        return "latest";
    }

    @Order(3)
    @Key("display-tags-repo")
    @Comment({
            "Our fork of the Text Display nametag plugin. Required on the SMP server:",
            "smp/src/main/resources/paper-plugin.yml declares it load: BEFORE, required: true,",
            "so the SMP plugin does not enable without it."
    })
    default String displayTagsRepo() {
        return "nordtal/papermc-display-tags";
    }

    @Order(4)
    @Key("display-tags-release")
    @Comment({
            "'latest' or an exact tag, exactly like season-release. Note that this repository's",
            "tags carry no leading 'v' - 2.0.0, not v2.0.0."
    })
    default String displayTagsRelease() {
        return "latest";
    }

    @Order(5)
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

    @Order(6)
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

    @Order(7)
    @Key("minecraft-version")
    @Comment({
            "The Minecraft version the network runs. Used as the game_versions filter against",
            "Modrinth and as the version whose builds are read from the PaperMC Fill API.",
            "",
            "This is not a value the updater may change on its own: a new Minecraft version is",
            "a season decision, and every plugin in the org is compiled against exactly one."
    })
    default String minecraftVersion() {
        return "26.2";
    }

    @Order(8)
    @Key("velocity-version")
    @Comment({
            "The Velocity version the proxy runs. Same rule as minecraft-version: the updater",
            "follows BUILDS within it and never moves the version itself."
    })
    default String velocityVersion() {
        return "4.1.1";
    }

    @Order(9)
    @Key("paper-build")
    @Comment({
            "Which build of minecraft-version the three Paper servers run: the word 'latest'",
            "for the newest STABLE build the Fill API lists, or an exact build number such",
            "as '121'.",
            "",
            "An exact number is how a rollback is expressed, exactly like season-release: the",
            "next apply installs that build, whether it is older or newer than what is there.",
            "It is a person's decision and this module never writes it. PAPER_BUILD in .env is",
            "a different thing - the build the entrypoint seeds an EMPTY cache with, once."
    })
    default String paperBuild() {
        return "latest";
    }

    @Order(10)
    @Key("velocity-build")
    @Comment({
            "Which build of velocity-version the proxy runs. Same rule as paper-build."
    })
    default String velocityBuild() {
        return "latest";
    }

    @Order(11)
    @Key("volumes-root")
    @Comment({
            "Where the four Minecraft volumes are mounted inside this container - one",
            "directory per compose service, named exactly as the service is:",
            "",
            "  <volumes-root>/network-control    <volumes-root>/limbo",
            "  <volumes-root>/hunger-games       <volumes-root>/smp",
            "",
            "A directory that is not there is reported as such rather than being created. This",
            "module refuses to invent a server that was not mounted: an updater that silently",
            "reports 'nothing installed' for a running SMP is worse than one that says the",
            "mount is missing."
    })
    default String volumesRoot() {
        return "/volumes";
    }

    @Order(12)
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

    @Order(13)
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

    @Order(14)
    @Key("download-timeout-seconds")
    @Comment({
            "How long a single jar may take to download during `updater apply`.",
            "",
            "Much larger than http-timeout-seconds and for a different reason: that one bounds six",
            "small JSON documents, this one bounds a Paper server jar of about 65 MB. Ten minutes",
            "is what deploy/minecraft/entrypoint.sh already allows itself for the same file."
    })
    default int downloadTimeoutSeconds() {
        return 600;
    }

    @Order(15)
    @Key("poll-interval-seconds")
    @Comment({
            "How often `updater serve` looks in update_request for work it was not told about.",
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

    @Order(16)
    @Key("bootstrap")
    @Comment({
            "Whether `updater serve` installs what is MISSING before it reports itself ready.",
            "",
            "This is what makes a deployment possible with no shell on the host. A Minecraft",
            "server refuses to start on an empty plugins folder, so without this a brand new",
            "stack needs `docker compose run --rm updater apply` typed by a person - and Arcane",
            "has no way to type it. With it, the first start of this container fills every empty",
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

    @Order(17)
    @Key("arcane")
    @Comment({
            "How the restart is actually performed: one redeploy of the whole compose project",
            "through Arcane's REST API.",
            "",
            "NOT the Docker socket, deliberately. A container holding /var/run/docker.sock can do",
            "anything on the host, and this is a container whose entire job is to download files",
            "from the internet and put them where servers will execute them. One API token is the",
            "cheaper half of that trade, and the redeploy then shows up in Arcane's own history",
            "instead of happening behind its back.",
            "",
            "LEAVE base-url EMPTY AND NOTHING BREAKS. Every other part of this module still works;",
            "the restart button says so and the redeploy is a click in Arcane."
    })
    ArcaneSpec arcane();

    @Order(18)
    @Key("backup")
    @Comment({
            "The nightly volume backup: which volumes are saved and which services are stopped",
            "while they are.",
            "",
            "THE UPDATER DOES NOT SCHEDULE THIS AND MUST NOT. `serve` has exactly one rule it is",
            "protected by - it does nothing at all until a row appears in update_request - and a",
            "timer here would be the end of it. The nightly row is written by `smp`, which already",
            "owns a daily clock for the farm world; see smp's config.yml#backup-time. An admin",
            "asks for one with /backup now. The consequence is written down rather than hidden: a",
            "season with `smp` down has no nightly backup and nothing else notices."
    })
    BackupSpec backup();

    /** What a {@code BACKUP} run saves and what it stops while it does. */
    @ConfigSpec
    interface BackupSpec {

        @Order(1)
        @Key("volumes")
        @Comment({
                "The Docker volumes to snapshot, by their REAL names - what `docker volume ls`",
                "prints, not the keys in compose.yml. Compose prefixes every volume with the",
                "project name, which compose.yml pins as `nordtal-s2`, so the two differ by that",
                "prefix and Arcane only knows the real one.",
                "",
                "WHY THESE AND NOT THE OTHERS. mc-smp is Nordtal - a hand-built world in no",
                "repository and in no release, and the only thing here that cannot be rebuilt.",
                "mc-network-control carries velocity.toml and the forwarding secret. bot-config",
                "and postgres-dumps are the bot's and the database's. The four *-plugins volumes",
                "are new on 2026-09-08 and hold the only hand-edited files in the deployment:",
                "every plugin's config.yml, smp's milestones.yml and sounds.yml, and the proxy's",
                "pack.yml with the resource pack's SHA-1 in it.",
                "",
                "WHAT IS DELIBERATELY ABSENT. postgres-data is never here: a snapshot of a live",
                "PGDATA is torn, and it fails at RESTORE rather than at backup, which is the worst",
                "place for it to fail. The pg_dump sidecar writes postgres-dumps instead and that",
                "is what is saved. mc-limbo and mc-hunger-games are absent too - limbo builds its",
                "world at every enable and the hunger games arena is a folder that is copied in,",
                "so both are rebuilt rather than restored. bot-jar and updater-jar are refilled by",
                "`updater bootstrap`.",
                "",
                "WHERE a snapshot goes is Arcane's decision and not this file's: its backup policy",
                "on each volume says local, S3 or both. Set that up once - see deploy/README.md."
        })
        default List<String> volumes() {
            return List.of("nordtal-s2_mc-smp",
                    "nordtal-s2_mc-smp-plugins",
                    "nordtal-s2_mc-network-control",
                    "nordtal-s2_mc-network-control-plugins",
                    "nordtal-s2_mc-limbo-plugins",
                    "nordtal-s2_mc-hunger-games-plugins",
                    "nordtal-s2_bot-config",
                    "nordtal-s2_postgres-dumps");
        }

        @Order(2)
        @Key("stop-services")
        @Comment({
                "Which compose services are stopped while the snapshot is taken, by the names",
                "Arcane's runtime endpoint reports - which are compose's service names.",
                "",
                "A SNAPSHOT OF A RUNNING PAPER SERVER IS A TORN ONE, and the way that surfaces is",
                "a region file that will not load, months later, on the one day somebody needs the",
                "backup. So the servers holding a saved volume go down first.",
                "",
                "ARCANE CAN DO THIS ITSELF AND IT IS TURNED OFF ON PURPOSE. A backup policy has a",
                "`Stop Containers` flag; leaving it on means Arcane stops the containers with no",
                "countdown and no warning to anybody standing in the world. The stopping is done",
                "here so that the thirty-second countdown every player sees runs first, and so",
                "that something is left running afterwards to say whether everything came back.",
                "",
                "limbo and hunger-games are absent: neither holds a world worth saving, and an",
                "outage with nothing to show for it is worse than no backup. Their plugins/",
                "volumes are still snapshotted - a config.yml is written at enable and at reload",
                "and at no other time, so there is nothing in flight to tear."
        })
        default List<String> stopServices() {
            return List.of("smp", "network-control", "bot");
        }

        @Order(3)
        @Key("patience-minutes")
        @Comment({
                "How long one volume's snapshot may take before the run gives up on it and starts",
                "the servers again.",
                "",
                "Generous, because the servers are already down and the alternative to waiting is",
                "starting them back up onto a half-written snapshot. Nordtal at border 4000 is",
                "several gigabytes and the first S3 upload of it is the slow one; every one after",
                "that is a Rustic delta. What this must not be is infinite: a backup that hangs",
                "would otherwise leave the network down until somebody noticed."
        })
        default int patienceMinutes() {
            return 60;
        }
    }

    /** Where Arcane is and how to ask it for a redeploy. */
    @ConfigSpec
    interface ArcaneSpec {

        @Order(1)
        @Key("base-url")
        @Comment({
                "Arcane's origin, with no trailing slash - https://arcane.example.com.",
                "",
                "NOT localhost. THE UPDATER IS A CONTAINER, and localhost inside it is the",
                "container, not the host Arcane runs on - so every restart fails with a connection",
                "error within milliseconds while Arcane sits there working perfectly. It is the",
                "value the browser bar shows, which is exactly why it gets copied here; it cost the",
                "first restart of the first deployment (finding 40). Use",
                "http://host.docker.internal:<port>, which compose.yml maps for this service,",
                "Arcane's container name if it shares a Docker network with this one, or the host's",
                "address on the network. A loopback value is warned about at startup and named",
                "again in the failure.",
                "",
                "Empty means 'no restart button anywhere'. That is a supported state, not a broken",
                "one: the updater reports what it would have done and an admin clicks Redeploy in",
                "Arcane themselves."
        })
        default String baseUrl() {
            return "";
        }

        @Order(2)
        @Key("api-key")
        @Comment({
                "A token from Arcane's Settings -> API Keys, sent as the X-Api-Key header.",
                "",
                "Belongs in the environment and not in this file:",
                "NORDTAL_UPDATER_ARCANE_API_KEY. An overridden value is never written back here."
        })
        default String apiKey() {
            return "";
        }

        @Order(3)
        @Key("environment")
        @Comment({
                "Which Docker environment in Arcane, as its ID. The local one - Arcane's own host,",
                "which is what this deployment is - is '0'; a remote agent is a UUID.",
                "",
                "Substituted into redeploy-path below. It is an ID and never a name."
        })
        default String environment() {
            return "0";
        }

        @Order(4)
        @Key("project")
        @Comment({
                "Which project, as its ID - and this is a UUID Arcane generated, NOT the compose",
                "project name. 'nordtal-s2' is not a value this setting accepts; a name here",
                "answers 404.",
                "",
                "Read it out of the browser URL with the project open, or from",
                "GET /api/environments/0/projects with the same token.",
                "",
                "No default on purpose: there is nothing to guess, and an empty one with a",
                "base-url set is refused at startup rather than at the moment somebody presses",
                "the button. Substituted into redeploy-path below."
        })
        default String project() {
            return "";
        }

        @Order(5)
        @Key("redeploy-path")
        @Comment({
                "The endpoint, with {environment} and {project} replaced by the two settings above.",
                "",
                "THIS DEFAULT IS NO LONGER A GUESS. It was read from Arcane's own source on",
                "2026-09-01 - backend/internal/project/handler.go, release v2.10.0 - where the",
                "operation is registered as POST /environments/{id}/projects/{projectId}/redeploy",
                "under the /api group. Arcane's public documentation still does not publish it,",
                "which is why it stays a setting: a version that moves the path is then one line",
                "in this file and not a release of ours.",
                "",
                "A 404 from here names both IDs, because a name in either of them is the likely",
                "cause and it is not visible in the URL."
        })
        default String redeployPath() {
            return "/api/environments/{environment}/projects/{project}/redeploy";
        }

        @Order(6)
        @Key("runtime-path")
        @Comment({
                "Where the updater reads the state of the project's services: one entry per",
                "service with its container id, its status and its Docker health. This is what",
                "turns \"and then everything came back\" from a hope into a check, and it is the",
                "only reason an update can report which server did not.",
                "",
                "Read from Arcane's own source on 2026-09-07, v2.10.0 and v2.10.2 alike -",
                "backend/internal/project/handler.go registers GET",
                "/environments/{id}/projects/{projectId}/runtime, and each service comes back",
                "carrying name, containerId, status and health. A setting for the same reason",
                "redeploy-path is one: the documentation does not publish it."
        })
        default String runtimePath() {
            return "/api/environments/{environment}/projects/{project}/runtime";
        }

        @Order(7)
        @Key("container-path")
        @Comment({
                "Where the updater stops and starts ONE container, with {container} replaced by",
                "the id runtime-path gave it and {action} by start or stop.",
                "",
                "This is the endpoint the whole update sequence rests on, and it is",
                "container-level rather than project-level for one reason: Arcane's project-level",
                "calls do stop AND start in a single request, and an update needs the gap between",
                "them - that gap is where the jars are replaced. Swapping them any other way is",
                "finding 147, which is what this design exists to end.",
                "",
                "It is also why the updater survives its own update: it never stops itself, so it",
                "is still running to start the others again and to say whether they came back.",
                "",
                "One caveat, measured from Arcane's source on 2026-09-07: its container stop",
                "hardcodes a 30-second timeout and does NOT honour compose.yml's",
                "stop_grace_period of 180s. Paper was measured shutting down in 3 seconds",
                "(deploy/README.md), so there is room - but a server that ever needs longer than",
                "thirty seconds to save will be killed, and that would show up as a corrupt",
                "region file rather than as an error here."
        })
        default String containerPath() {
            return "/api/environments/{environment}/containers/{container}/{action}";
        }

        @Order(8)
        @Key("backup-path")
        @Comment({
                "Where the updater starts a volume backup and reads its state, with {volume}",
                "replaced by the Docker volume name. POST starts one, GET lists them.",
                "",
                "Read from Arcane's own source on 2026-09-08, v2.10.2 -",
                "backend/internal/volume/handler.go registers both under",
                "/environments/{id}/volumes/{volumeName}/backups: the POST answers 202 with the",
                "new backup's entry (its id and a status of `running`), the GET answers a",
                "paginated list of entries each carrying id and status. A setting for the same",
                "reason redeploy-path is one: the documentation does not publish either.",
                "",
                "The POST body is empty on purpose. Arcane then loads the volume's OWN backup",
                "policy and uses its destination - local, S3 or both - so where a snapshot goes",
                "stays a decision taken once in Arcane's interface rather than a second copy of it",
                "in this file. A volume with no policy is backed up locally.",
                "",
                "A 409 means a backup of that volume is already running, which is not a failure of",
                "this run: it is reported as such and the servers still come back."
        })
        default String backupPath() {
            return "/api/environments/{environment}/volumes/{volume}/backups";
        }

        @Order(9)
        @Key("timeout-seconds")
        @Comment({
                "How long to wait for the redeploy call.",
                "",
                "Short on purpose. Arcane answers a long-running operation as a stream of",
                "newline-delimited JSON, and this container is one of the things the redeploy",
                "takes down - it will be killed part way through reading that stream. So the call",
                "only ever waits for the response to BEGIN. Being killed here is the expected",
                "outcome and the next start reads it as success: the request row it left behind",
                "says so."
        })
        default int timeoutSeconds() {
            return 20;
        }
    }
}
