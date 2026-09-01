package eu.nordtal.s2.updater.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

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

    @Order(10)
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

    @Order(11)
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

    @Order(12)
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

    @Order(13)
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

    @Order(14)
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

    /** Where Arcane is and how to ask it for a redeploy. */
    @ConfigSpec
    interface ArcaneSpec {

        @Order(1)
        @Key("base-url")
        @Comment({
                "Arcane's origin, with no trailing slash - https://arcane.example.com.",
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
        @Key("project")
        @Comment({
                "The project as Arcane names it - for this deployment, the compose project name",
                "'nordtal-s2'. Substituted into redeploy-path below."
        })
        default String project() {
            return "nordtal-s2";
        }

        @Order(4)
        @Key("redeploy-path")
        @Comment({
                "THIS DEFAULT IS A GUESS AND HAS NEVER BEEN CALLED.",
                "",
                "Arcane's public documentation describes token authentication and says that",
                "project deploy, redeploy, pull and build exist as streaming operations - it does",
                "NOT give the paths (read 2026-09-01). Those are on /api/docs on your own instance.",
                "So this is a setting rather than a constant: when the real path turns out to be",
                "something else, it is one line in this file and not a release.",
                "",
                "{project} is replaced by the project setting above. A 404 from here is reported",
                "with that sentence attached, so nobody spends an evening on it."
        })
        default String redeployPath() {
            return "/api/projects/{project}/redeploy";
        }

        @Order(5)
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
