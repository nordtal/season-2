package eu.nordtal.s2.steward.ui.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.Secret;

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
        "CLIENT_SECRET, NORDTAL_STEWARD_UI_WORKER_TOKEN, NORDTAL_STEWARD_UI_DEPLOYER_TOKEN and",
        "NORDTAL_STEWARD_UI_WEB_PUSH_PRIVATE_KEY. jcore lets an environment variable win over any",
        "key here and never writes it back."
})
public interface UiSpec {

    @Order(1)
    @Name("Port")
    @Key("port")
    @Comment({
            "The port inside the container. Caddy is in front of it and terminates TLS; nothing",
            "else ever talks to this port."
    })
    @NoExplanationNeeded
    default int port() {
        return 8080;
    }

    @Order(2)
    @Name("Public URL")
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
    @Explain("Pins both the Discord redirect URI and the WebAuthn origin check - not the same value as webauthn.relying-party-id below, though the two are cross-checked at startup so they cannot quietly drift apart.")
    default String publicUrl() {
        return "https://steward.dev.nordtal.eu";
    }

    @Order(3)
    @Name("Worker")
    @Key("worker")
    @Comment("Where steward-worker's internal API is, and the secret it expects.")
    @NoExplanationNeeded
    WorkerSpec worker();

    @Order(4)
    @Name("Discord")
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
    @Explain("The bot's own Discord application, reused rather than a separate one. Discord only decides who may attempt to register a security key - webauthn below is a second, mandatory gate behind it.")
    DiscordSpec discord();

    @Order(5)
    @Name("Session length (days)")
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
    @Explain("Only defensible because a security key stands in front of every dangerous action - raising this without keeping that boundary intact turns a stolen cookie into a month-long back door.")
    default int sessionDays() {
        return 30;
    }

    @Order(6)
    @Name("Alerts")
    @Key("alerts")
    @Comment({
            "When the traffic light on the start page turns yellow or red (concept 10c).",
            "",
            "These are the two thresholds that are a matter of taste; the other two triggers - a",
            "service that is down, and a missing backup - are not adjustable and are not meant to",
            "be. A stopped SMP is not a preference."
    })
    @Explain("Only these two thresholds are a matter of taste - a stopped service and a missing backup are separate, non-adjustable triggers on the same traffic light.")
    AlertSpec alerts();

    @Order(7)
    @Name("Avatars")
    @Key("avatars")
    @Comment({
            "Where a Minecraft head image comes from (steward/45).",
            "",
            "Moved here from steward/44, which found the two open questions and left them: a",
            "column in the database would have gone stale the moment this URL changed, so the",
            "identity display builds the address itself from mc_uuid plus the base below."
    })
    @NoExplanationNeeded
    AvatarSpec avatars();

    @Order(8)
    @Name("Deployer")
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
    @Explain("A second service and a second secret from steward-worker's own - the split is this stack's privilege boundary, so one stolen token can never both read and recreate a container.")
    DeployerSpec deployer();

    @Order(9)
    @Name("Passkeys")
    @Key("webauthn")
    @Comment({
            "The second factor (§10a): which domain a registered security key belongs to.",
            "",
            "Added on 2026-09-14 with migration V20. A file written before that gains this section",
            "at its default on the next load - the same measured behaviour RenamedKeyTest holds",
            "for a scalar key, and AddedSectionTest holds for this whole block - so it is a commit",
            "and not a deployment step."
    })
    @NoExplanationNeeded
    WebAuthnSpec webauthn();

    @Order(10)
    @Name("Web push")
    @Key("web-push")
    @Comment({
            "Web Push (concept §10c / steward/98): the traffic light reaching a phone's lock",
            "screen rather than only the page.",
            "",
            "Both keys are a VAPID keypair, generated once per deployment and never hardcoded -",
            "run `docker exec nordtal-s2-steward-ui-1 steward-ui generate-vapid-keys` and paste",
            "the two lines it prints here, or the private one into",
            "NORDTAL_STEWARD_UI_WEB_PUSH_PRIVATE_KEY instead, which is this file's own header",
            "advice for a secret. Both blank is read as \"not configured\": the subscribe button",
            "is not drawn and no watch runs, the same shape worker.token and deployer.token",
            "already use for \"not wired up yet\"."
    })
    @NoExplanationNeeded
    WebPushSpec webPush();

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
        @Name("Relying party ID")
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
        @Explain("The one decision here that cannot be taken back: changing it invalidates every registered key rather than migrating them, and every subdomain of the value chosen becomes a trusted sign-in surface for this interface.")
        default String relyingPartyId() {
            return "nordtal.eu";
        }
    }

    /** Where steward-deployer's internal API is. */
    @ConfigSpec
    interface DeployerSpec {

        @Order(1)
        @Name("Base URL")
        @Key("base-url")
        @Comment("The compose service name and the API port - no TLS, it never leaves the network.")
        @NoExplanationNeeded
        default String baseUrl() {
            return "http://steward-deployer:8081";
        }

        @Order(2)
        @Name("Token")
        @Key("token")
        @Comment({
                "The shared secret, the same one steward-deployer is given as",
                "NORDTAL_STEWARD_DEPLOYER_TOKEN. From the environment in a deployment; this file",
                "holds an empty string rather than a secret somebody might commit."
        })
        @Explain("The same secret steward-deployer expects as NORDTAL_STEWARD_DEPLOYER_TOKEN, not a second one to invent - empty leaves the recreate button unoffered rather than offered and failing.")
        default String token() {
            return "";
        }
    }

    /**
     * A VAPID keypair (steward/98) - proof to a push service of which server sent a message,
     * without which every browser's subscription is worthless the moment it is asked to accept one.
     *
     * <p>Generated once with {@code steward-ui generate-vapid-keys} (see {@code StewardUi.main}),
     * never in code: a keypair baked into the jar would be the same "identity" for every deployment
     * that ever ran it, and a push service has no way to tell those apart from an attacker who
     * downloaded the same jar.</p>
     */
    @ConfigSpec
    interface WebPushSpec {

        @Order(1)
        @Name("Public key")
        @Key("public-key")
        @Comment({
                "The public half, X509-encoded and base64 - `VapidKeys.x509PublicKey`. Not a",
                "secret: it is handed to every browser that subscribes, as the",
                "`applicationServerKey` the Push API asks for."
        })
        @NoExplanationNeeded
        default String publicKey() {
            return "";
        }

        @Order(2)
        @Name("Private key")
        @Key("private-key")
        @Comment({
                "The private half, PKCS8-encoded and base64 - `VapidKeys.pkcs8PrivateKey`. This",
                "is what signs the VAPID JWT on every push, so a copy of it is everything needed",
                "to send a notification that claims to be this deployment.",
                "",
                "NOT COVERED BY THE NAME HEURISTIC ConfigEntry.isSecretKey ORDINARILY USES (steward/115",
                "found this gap once already and it must not reopen here): a key ending in",
                "\"-key\" rather than containing \"secret\", \"token\" or \"password\" would have",
                "been sent to the browser in plain text without @Secret saying otherwise."
        })
        @Secret
        @NoExplanationNeeded
        default String privateKey() {
            return "";
        }

        @Order(3)
        @Name("Subject")
        @Key("subject")
        @Comment({
                "Who a push service may contact about this VAPID identity, if it ever needs to -",
                "a mailto: address or an https:// URL. The library refuses anything else outright."
        })
        @NoExplanationNeeded
        default String subject() {
            return "mailto:admin@nordtal.eu";
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
        @Name("Disk usage (percent)")
        @Key("disk-percent")
        @Comment({
                "How full the disk may get before the start page says so. Measured on this host on",
                "2026-09-12: 12.1 G of 193.6 G, i.e. 6 percent - so the margin here is wide, and it",
                "is meant to fire long before anything actually stops."
        })
        @NoExplanationNeeded
        default int diskPercent() {
            return 85;
        }

        @Order(2)
        @Name("Memory usage (percent)")
        @Key("memory-percent")
        @Comment({
                "The same for memory. No container in this stack sets a limit, so this is the share",
                "of the whole machine and not of anybody's budget - four Minecraft servers shared",
                "6.2 of 15.6 GiB when this was measured."
        })
        @Explain("A share of the WHOLE host's memory, not of any per-container limit - none is set, so a busy server here means less headroom for everything else, not a violation of its own quota.")
        default int memoryPercent() {
            return 90;
        }

        @Order(3)
        @Name("Backup age (hours)")
        @Key("backup-age-hours")
        @Comment({
                "How old the newest finished backup may be before the traffic light turns RED.",
                "The nightly clock runs once a day, so anything under 24 would fire on a normal",
                "morning; 36 leaves one missed night visible and two nights impossible to miss.",
                "",
                "This one counts files on the disk, not runs that reported success. Run 23 reported",
                "success having saved nothing at all, which is why."
        })
        @Explain("Counts files actually on disk, not runs that reported success - a run once reported success while saving nothing at all, which is why this does not trust the run's own verdict.")
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
        @Name("Minecraft head base URL")
        @Key("minecraft-head-base-url")
        @Comment({
                "Till's choice, 2026-09-19: api.mineatar.io. mc-heads.net stood here from",
                "2026-09-18 and crafatar.com before that, which was measured failing to answer at",
                "all (steward/111) - the fault was the service, not the caller. A free,",
                "unaffiliated service in all three cases - see season-2/README.md - so the identity",
                "display treats a non-answer as a placeholder and never blocks the page on it.",
                "",
                "NOTED RATHER THAN HIDDEN: every render sends this service the mc_uuid being",
                "looked at, which is the one piece of information about a player that leaves this",
                "deployment on the strength of an admin merely opening a page.",
                "",
                "WHY THERE IS A '?scale=16' ON THE END. Measured 2026-09-19: the blank endpoint",
                "answers 32x32 pixels, where mc-heads answered 180x180. A head is drawn at up to 32",
                "CSS pixels, which is 96 real ones on a 3x phone display, so the blank endpoint",
                "would be visibly soft. scale=16 is 128x128 and 472 bytes.",
                "",
                "The identity display inserts '/<uuid>' BEFORE the query, with the hyphens stripped",
                "out of the uuid. So this is the address up to and including the path segment",
                "before it, optionally followed by a query - and never a trailing slash."
        })
        @Explain("Every render sends this third-party service the mc_uuid being looked at - the one piece of player information that leaves this deployment on the strength of an admin merely opening a page.")
        default String minecraftHeadBaseUrl() {
            return "https://api.mineatar.io/face?scale=16";
        }
    }

    /** Where steward-worker's internal API is. */
    @ConfigSpec
    interface WorkerSpec {

        @Order(1)
        @Name("Base URL")
        @Key("base-url")
        @Comment("The compose service name and the API port - no TLS, it never leaves the network.")
        @NoExplanationNeeded
        default String baseUrl() {
            return "http://steward-worker:8082";
        }

        @Order(2)
        @Name("Token")
        @Key("token")
        @Comment({
                "The shared secret, the same one steward-worker is given. Empty means this",
                "interface cannot read anything about a container, and says so on every page that",
                "would have shown one - rather than drawing an empty table that looks like a stack",
                "with nothing running."
        })
        @Explain("Empty means this interface can read nothing about a container and says so on every page that would have shown one, rather than drawing an empty table that looks like nothing is running.")
        default String token() {
            return "";
        }
    }

    /** The Discord application and the guild whose roles decide who may sign in. */
    @ConfigSpec
    interface DiscordSpec {

        @Order(1)
        @Name("Client ID")
        @Key("client-id")
        @Comment("The application's id. Public - it is in the URL a browser is sent to.")
        @NoExplanationNeeded
        default String clientId() {
            return "";
        }

        @Order(2)
        @Name("Client secret")
        @Key("client-secret")
        @Comment({
                "From the environment, never from this file. Empty means nobody can sign in and",
                "the sign-in page says which value is missing instead of failing at Discord."
        })
        @Explain("Empty means nobody can sign in at all - the sign-in page names which value is missing rather than failing silently at Discord's side.")
        default String clientSecret() {
            return "";
        }

        @Order(3)
        @Name("Guild ID")
        @Key("guild-id")
        @Comment({
                "The guild whose roles are read. This is a SECOND copy of what the bot's",
                "access.yml already says, and that is deliberate: two processes, two volumes, no",
                "shared file. If they ever disagree, this one decides who gets into the interface",
                "and the bot's decides who gets into the game."
        })
        @Explain("A second, independent copy of the same guild id the bot's access.yml holds - if the two ever disagree, this one decides who reaches the interface and the bot's decides who reaches the game, not each other.")
        default String guildId() {
            return "";
        }

        @Order(4)
        @Name("Bot token")
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
        @Explain("The bot's own token, used only to label roles and channels by name in the config editor - steward never sends a message or changes a role with it, and never places it in a log, an answer or in front of a browser.")
        default String botToken() {
            return "";
        }
    }
}
