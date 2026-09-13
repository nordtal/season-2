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
        "CLIENT_SECRET and NORDTAL_STEWARD_UI_WORKER_TOKEN. jcore lets an environment variable",
        "win over any key here and never writes it back."
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
