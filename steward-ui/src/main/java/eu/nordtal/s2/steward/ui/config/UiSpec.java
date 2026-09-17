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
            "IT IS NOT WHAT A SECURITY KEY IS BOUND TO, although an earlier version of this",
            "comment said so. Till decided on 2026-09-14 that WebAuthn binds to the parent domain",
            "nordtal.eu, so that a key registered against this address still works on the",
            "production one. That is `webauthn.relying-party-id` below, and the two are checked",
            "against each other at startup: a key bound to a domain this address is not under is a",
            "sign-in every browser refuses in silence.",
            "",
            "It IS what the browser is allowed to have been talking to when a key answers. The",
            "origin in a WebAuthn response is compared against this address exactly, which is why",
            "changing it is a change to the second factor as well as to the Discord redirect."
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
            "DISCORD IS NO LONGER THE WHOLE OF THE AUTHENTICATION. §10a wants a security key",
            "after the Discord login, and since V20 an account with no registered key reaches the",
            "setup page and nothing else - see `webauthn` below. What Discord still decides on its",
            "own is WHO may register one at all: the admin role below is the gate in front of the",
            "gate."
    })
    DiscordSpec discord();

    @Order(5)
    @Key("session-days")
    @Comment({
            "How long a signed-in session lives before the browser has to sign in again.",
            "",
            "Sessions are rows in PostgreSQL (migration V19), so a restart of this container no",
            "longer ends them - which is what makes a number this large sane. It is absolute and",
            "does not slide: thirty days from the sign-in, used daily or not at all.",
            "",
            "THIRTY DAYS IS HALF OF A TRADE AND MUST NOT BE KEPT WITHOUT THE OTHER HALF. It is",
            "only defensible because a security key stands in front of everything dangerous - an",
            "update, a backup, a restart, a line into a server console, granting access. A long",
            "session that could do those things on the strength of a cookie alone would be a back",
            "door with a month's lease. Whoever shortens the list of protected actions is also",
            "deciding about this number.",
            "",
            "Replaced `session-hours` on 2026-09-14. MEASURED, not assumed (RenamedKeyTest): a",
            "file written before the rename gains `session-days: 30` on the next load, because",
            "jcore preserves what is in a file but still adds a key that is missing from it. So",
            "this is a commit and not a deployment step - unlike a changed DEFAULT, which really",
            "does never reach a file that already has the key. The old `session-hours: 12` line",
            "is left behind as a dead key and can be deleted whenever somebody is in there."
    })
    default int sessionDays() {
        return 30;
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
    @Key("avatars")
    @Comment({
            "Where a Minecraft head image comes from (steward/45).",
            "",
            "Moved here from steward/44, which found the two open questions and left them: a",
            "column in the database would have gone stale the moment this URL changed, so the",
            "identity display builds the address itself from mc_uuid plus the base below."
    })
    AvatarSpec avatars();

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

    @Order(9)
    @Key("webauthn")
    @Comment({
            "The second factor (§10a): which domain a registered security key belongs to.",
            "",
            "Added on 2026-09-14 with migration V20. A file written before that gains this section",
            "at its default on the next load - the same measured behaviour RenamedKeyTest holds",
            "for a scalar key, and AddedSectionTest holds for this whole block - so it is a commit",
            "and not a deployment step."
    })
    WebAuthnSpec webauthn();

    /**
     * Which domain a security key is registered against.
     *
     * <p>One key, because the other half of the pair - what the browser's dialog calls this
     * service - is a product name and not a property of a deployment. It is written in code as
     * "Nordtal Steward" and a second copy of it in a YAML file would only ever be a way for two
     * deployments to disagree about their own name.</p>
     */
    @ConfigSpec
    interface WebAuthnSpec {

        @Order(1)
        @Key("relying-party-id")
        @Comment({
                "The domain a key is bound to. THE ONE DECISION HERE THAT CANNOT BE TAKEN BACK.",
                "",
                "WebAuthn calls this the Relying Party ID, and every key is registered against it.",
                "`nordtal.eu` means a key works on steward.dev.nordtal.eu, on the production",
                "address later, and on anything else that ever appears under nordtal.eu. Changing",
                "it does not migrate keys - it invalidates every one of them, and everybody has",
                "to register again. There is no command for that yet: today the way back is a",
                "row deleted from `steward_credential` on the host, per account, by hand.",
                "",
                "THE PRICE, WRITTEN DOWN WHERE THE VALUE IS: every page under nordtal.eu may ask",
                "the browser for this key. A subdomain that is taken over - a forgotten test",
                "server, a status page, a BlueMap instance - is a working sign-in form for",
                "Steward. Till chose this on 2026-09-14 with that description, in exchange for a",
                "key surviving the move to production. The rule that follows is: no subdomain of",
                "nordtal.eu gets an application that is not trusted as much as this one is.",
                "",
                "It must be `public-url`'s host or a parent of it. A browser silently refuses any",
                "other combination, so it is refused loudly here instead."
        })
        default String relyingPartyId() {
            return "nordtal.eu";
        }
    }

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
                "success having saved nothing at all, which is why."
        })
        default int backupAgeHours() {
            return 36;
        }
    }

    /**
     * Where the Minecraft head image in the identity display comes from.
     *
     * <p>A face is a pure function of {@code mc_uuid} and this base URL - see
     * {@code eu.nordtal.s2.common.access.MinecraftProfile}'s class comment for why the image
     * itself is never a column. Changing the service here is a config edit, never a migration.</p>
     */
    @ConfigSpec
    interface AvatarSpec {

        @Order(1)
        @Key("minecraft-head-base-url")
        @Comment({
                "Till's choice, 2026-09-15: Crafatar. A free, unaffiliated service - see",
                "season-2/README.md - so the identity display treats a non-answer as a placeholder",
                "and never blocks the page on it.",
                "",
                "NOTED RATHER THAN HIDDEN: every render sends this service the mc_uuid being",
                "looked at, which is the one piece of information about a player that leaves this",
                "deployment on the strength of an admin merely opening a page.",
                "",
                "The identity display appends '/<uuid>' itself; this is the address up to and",
                "including the path segment before the uuid, with no trailing slash."
        })
        default String minecraftHeadBaseUrl() {
            return "https://crafatar.com/avatars";
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
