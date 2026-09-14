package eu.nordtal.s2.steward.ui.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code steward-ui.yml} - what the web interface needs to know about the rest of the stack.
 *
 * <p>Nothing here is a secret in the file itself. Every value that is one arrives as an environment
 * variable, which jcore lets override any key and never writes back, so the file on disk stays
 * something you could paste into an issue.</p>
 */
@ConfigSpec(header = {
        "Nordtal Steward - the web interface.",
        "",
        "THIS PROCESS HOLDS NO DOCKER SOCKET and must never be given one (concept §3). Everything",
        "it knows about a container it asks steward-worker for, over the internal network, with",
        "the shared secret below. That hop is the whole point: this is the part of the stack an",
        "attacker reaches first, and it can therefore do the least.",
        "",
        "Secrets are environment variables, not values in this file: NORDTAL_STEWARD_UI_DISCORD_",
        "CLIENT_SECRET, NORDTAL_STEWARD_UI_WORKER_TOKEN and NORDTAL_STEWARD_UI_DEPLOYER_TOKEN.",
        "jcore lets an environment variable win over any key here and never writes it back."
})
public interface UiSpec {

    @Order(1)
    @Key("port")
    @Comment({
            "The port inside the container. Caddy is in front of it and terminates TLS; nothing",
            "else ever talks to this port."
    })
    default int port() {
        return 8080;
    }

    @Order(2)
    @Key("public-url")
    @Comment({
            "Where a browser reaches this interface, with scheme and no trailing slash.",
            "",
            "It is written down rather than guessed from the request, because a redirect URI that",
            "follows the Host header is a redirect URI an attacker can choose. Discord is given",
            "this + /auth/callback and refuses anything else.",
            "",
            "THE NAME IS CHOSEN ONCE. WebAuthn - which this alpha does not have yet - binds every",
            "registered key to it, so changing it later invalidates all of them."
    })
    default String publicUrl() {
        return "https://steward.dev.nordtal.eu";
    }

    @Order(3)
    @Key("worker")
    @Comment("Where steward-worker's internal API is, and the secret it expects.")
    WorkerSpec worker();

    @Order(4)
    @Key("discord")
    @Comment({
            "The Discord application this interface signs people in with, and the guild it reads",
            "their roles from.",
            "",
            "It is the BOT'S application, reused (Till's decision, 2026-09-12): one application,",
            "one client id, a second secret. The scopes are fixed in code - `identify` and",
            "`guilds.members.read` - which is what lets this process read the roles of the person",
            "signing in WITHOUT ever holding the bot's token.",
            "",
            "WHAT IS MISSING AND IS NOT PRETENDED OTHERWISE: §10a wants a security key after the",
            "Discord login, always, because otherwise a stolen Discord session is the whole of the",
            "authentication. WebAuthn is not built in this alpha; the sign-in page says so."
    })
    DiscordSpec discord();

    @Order(5)
    @Key("session-hours")
    @Comment({
            "How long a signed-in session lives before the browser has to sign in again.",
            "",
            "Sessions are in memory, so a restart of this container ends all of them. That is a",
            "property rather than a plan: the alternative is a session store to back up and keep",
            "consistent, for three admins who can sign in again in four seconds."
    })
    default int sessionHours() {
        return 12;
    }

    @Order(6)
    @Key("alerts")
    @Comment({
            "When the traffic light on the start page turns yellow or red (concept 10c).",
            "",
            "These are the two thresholds that are a matter of taste; the other two triggers - a",
            "service that is down, and a missing backup - are not adjustable and are not meant to",
            "be. A stopped SMP is not a preference."
    })
    AlertSpec alerts();

    @Order(7)
    @Key("configs")
    @Comment({
            "Where the other services' config files are mounted, so this interface can show them.",
            "",
            "One directory per compose service, named after it: /configs/steward-worker/steward.yml,",
            "/configs/smp/nordtal-smp/config.yml. Each is that service's own config volume mounted",
            "here a second time - read-write where an operator should be able to change something,",
            "read-only where they should not. A volume that is not mounted is not an error: the",
            "page lists what it finds and says nothing about what it cannot see."
    })
    ConfigsSpec configs();

    @Order(8)
    @Key("deployer")
    @Comment({
            "Where steward-deployer's internal API is, and the secret it expects.",
            "",
            "IT IS A SECOND SERVICE AND A SECOND SECRET, not the worker's. The split is the whole",
            "privilege boundary of this stack: the deployer may create containers and the worker",
            "may not, so one stolen token must not be both. What this interface asks it for is one",
            "thing - recreate a service whose image has drifted (10a.4) - and it asks it by name,",
            "never with a command line.",
            "",
            "Empty means the button is not offered and the page says why, rather than offering a",
            "button that fails."
    })
    DeployerSpec deployer();

    /** Where steward-deployer's internal API is. */
    @ConfigSpec
    interface DeployerSpec {

        @Order(1)
        @Key("base-url")
        @Comment("The compose service name and the API port - no TLS, it never leaves the network.")
        default String baseUrl() {
            return "http://steward-deployer:8081";
        }

        @Order(2)
        @Key("token")
        @Comment({
                "The shared secret, the same one steward-deployer is given as",
                "NORDTAL_STEWARD_DEPLOYER_TOKEN. From the environment in a deployment; this file",
                "holds an empty string rather than a secret somebody might commit."
        })
        default String token() {
            return "";
        }
    }

    /** Where the other services' config files are mounted. */
    @ConfigSpec
    interface ConfigsSpec {

        @Order(1)
        @Key("root")
        @Comment({
                "The mount point inside this container. Everything under it that ends in .yml is",
                "offered; nothing outside it can be reached, because a file is looked up by",
                "matching what the browser asked for against the list of files actually found",
                "rather than by joining a path onto this one."
        })
        default String root() {
            return "/configs";
        }
    }

    /**
     * The thresholds of the traffic light.
     *
     * <p>They live here rather than in the browser because the traffic light also has to fire
     * outside the interface - into the Discord admin channel - and a threshold kept in somebody's
     * localStorage cannot be read by anything that is not that browser.</p>
     */
    @ConfigSpec
    interface AlertSpec {

        @Order(1)
        @Key("disk-percent")
        @Comment({
                "How full the disk may get before the start page says so. Measured on this host on",
                "2026-09-12: 12.1 G of 193.6 G, i.e. 6 percent - so the margin here is wide, and it",
                "is meant to fire long before anything actually stops."
        })
        default int diskPercent() {
            return 85;
        }

        @Order(2)
        @Key("memory-percent")
        @Comment({
                "The same for memory. No container in this stack sets a limit, so this is the share",
                "of the whole machine and not of anybody's budget - four Minecraft servers shared",
                "6.2 of 15.6 GiB when this was measured."
        })
        default int memoryPercent() {
            return 90;
        }

        @Order(3)
        @Key("backup-age-hours")
        @Comment({
                "How old the newest finished backup may be before the traffic light turns RED.",
                "The nightly clock runs once a day, so anything under 24 would fire on a normal",
                "morning; 36 leaves one missed night visible and two nights impossible to miss.",
                "",
                "This one counts files on the disk, not runs that reported success. Run 23 reported",
                "success having saved nothing at all (todo.md A23), which is why."
        })
        default int backupAgeHours() {
            return 36;
        }
    }

    /** Where steward-worker's internal API is. */
    @ConfigSpec
    interface WorkerSpec {

        @Order(1)
        @Key("base-url")
        @Comment("The compose service name and the API port - no TLS, it never leaves the network.")
        default String baseUrl() {
            return "http://steward-worker:8082";
        }

        @Order(2)
        @Key("token")
        @Comment({
                "The shared secret, the same one steward-worker is given. Empty means this",
                "interface cannot read anything about a container, and says so on every page that",
                "would have shown one - rather than drawing an empty table that looks like a stack",
                "with nothing running."
        })
        default String token() {
            return "";
        }
    }

    /** The Discord application and the guild whose roles decide who may sign in. */
    @ConfigSpec
    interface DiscordSpec {

        @Order(1)
        @Key("client-id")
        @Comment("The application's id. Public - it is in the URL a browser is sent to.")
        default String clientId() {
            return "";
        }

        @Order(2)
        @Key("client-secret")
        @Comment({
                "From the environment, never from this file. Empty means nobody can sign in and",
                "the sign-in page says which value is missing instead of failing at Discord."
        })
        default String clientSecret() {
            return "";
        }

        @Order(3)
        @Key("guild-id")
        @Comment({
                "The guild whose roles are read. This is a SECOND copy of what the bot's",
                "access.yml already says, and that is deliberate: two processes, two volumes, no",
                "shared file. If they ever disagree, this one decides who gets into the interface",
                "and the bot's decides who gets into the game."
        })
        default String guildId() {
            return "";
        }

        @Order(4)
        @Key("bot-token")
        @Comment({
                "The Discord bot's token, and it is here for ONE thing: asking Discord what the",
                "guild's roles and channels are called, so the configuration editor can offer a",
                "list to pick from instead of a field to transcribe an eighteen-digit id into.",
                "",
                "It is the same token discord-bot uses. Steward only reads with it - it never",
                "sends a message, never changes a role, and never puts the token in an answer, in",
                "a log or in front of a browser. Empty means the pickers fall back to a text",
                "field, which still works; it is the names that are missing, not the setting.",
                "",
                "From the environment, never from this file."
        })
        default String botToken() {
            return "";
        }

        @Order(5)
        @Key("admin-role")
        @Comment({
                "The role id that may sign in. Everybody else is refused after Discord has",
                "confirmed who they are - which is the right order: the refusal can then name the",
                "person and the role they are missing, instead of being an anonymous no.",
                "",
                "Empty means NOBODY may sign in. It is not a default that lets everyone in: an",
                "interface that can stop a server and read a token is not a thing to open by",
                "forgetting a value."
        })
        default String adminRole() {
            return "";
        }
    }
}
