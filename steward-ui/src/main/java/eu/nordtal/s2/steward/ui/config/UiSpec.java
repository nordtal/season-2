package eu.nordtal.s2.steward.ui.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Secret;

/**
 * {@code steward-ui.yml}: what the web interface knows about the rest of the stack.
 *
 * Every secret arrives as an environment variable, so the file itself holds none.
 */
@ConfigSpec(
        header = {
            "Nordtal Steward, the web interface.",
            "",
            "This process holds no Docker socket and must never be given one: it asks steward-worker",
            "for everything about a container, since it is the part an attacker reaches first.",
            "",
            "Secrets are environment variables that win over this file and are never written back:",
            "NORDTAL_STEWARD_UI_DISCORD_CLIENT_SECRET, NORDTAL_STEWARD_UI_WORKER_TOKEN,",
            "NORDTAL_STEWARD_UI_DEPLOYER_TOKEN and NORDTAL_STEWARD_UI_WEB_PUSH_PRIVATE_KEY."
        })
public interface UiSpec {

    @Order(1)
    @Name("Port")
    @Key("port")
    @Comment("The port inside the container, reached only through Caddy, which terminates TLS.")
    @NoExplanationNeeded
    default int port() {
        return 8080;
    }

    @Order(2)
    @Name("Public URL")
    @Key("public-url")
    @Comment({
        "Where a browser reaches this interface, with scheme and no trailing slash.",
        "Written down rather than taken from the Host header, which an attacker could choose.",
        "It is the Discord redirect base and the exact WebAuthn origin, and must lie under",
        "webauthn.relying-party-id; startup checks both."
    })
    @Explain(
            "Pins both the Discord redirect URI and the WebAuthn origin, and must lie under webauthn.relying-party-id, which startup checks.")
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
        "The bot's own Discord application, reused with a second secret, and the guild its roles",
        "are read from. The scopes identify and guilds.members.read read the signer's roles",
        "without the bot's token. Discord decides who may register a security key; webauthn",
        "below is the second gate."
    })
    @Explain(
            "The bot's own Discord application, reused. Discord only decides who may register a security key; webauthn below is a second, mandatory gate.")
    DiscordSpec discord();

    @Order(5)
    @Name("Session length (days)")
    @Key("session-days")
    @Comment({
        "How long a signed-in session lives, absolute and not sliding. Sessions are rows in",
        "PostgreSQL, so a restart does not end them.",
        "Thirty days is only defensible because a security key guards every dangerous action;",
        "whoever shortens that list is also deciding about this number."
    })
    @Explain(
            "Only defensible because a security key guards every dangerous action; without that, a stolen cookie is a month-long back door.")
    default int sessionDays() {
        return 30;
    }

    @Order(6)
    @Name("Alerts")
    @Key("alerts")
    @Comment({
        "When the start page's traffic light turns yellow or red. A service that is down and a",
        "missing backup are further triggers, deliberately not adjustable."
    })
    @Explain(
            "Only these thresholds are a matter of taste; a stopped service and a missing backup are fixed triggers on the same light.")
    AlertSpec alerts();

    @Order(7)
    @Name("Avatars")
    @Key("avatars")
    @Comment("Where a Minecraft head image comes from; the address is built from mc_uuid and this base.")
    @NoExplanationNeeded
    AvatarSpec avatars();

    @Order(8)
    @Name("Deployer")
    @Key("deployer")
    @Comment({
        "Where steward-deployer's internal API is, and its own secret, separate from the worker's",
        "so one stolen token cannot both read and recreate a container. Empty hides the recreate",
        "button, and the page says why."
    })
    @Explain(
            "A second service with a second secret: the split is this stack's privilege boundary, so one stolen token cannot both read and recreate a container.")
    DeployerSpec deployer();

    @Order(9)
    @Name("Passkeys")
    @Key("webauthn")
    @Comment("The second factor: which domain a registered security key belongs to.")
    @NoExplanationNeeded
    WebAuthnSpec webauthn();

    @Order(10)
    @Name("Web push")
    @Key("web-push")
    @Comment({
        "Web Push: the traffic light reaching a phone's lock screen. Both keys are a VAPID keypair",
        "generated once per deployment with `steward-ui generate-vapid-keys`; both blank means",
        "not configured, and no subscribe button is drawn."
    })
    @NoExplanationNeeded
    WebPushSpec webPush();

    /** Which domain a security key is registered against; the display name is fixed in code. */
    @ConfigSpec
    interface WebAuthnSpec {

        @Order(1)
        @Name("Relying party ID")
        @Key("relying-party-id")
        @Comment({
            "The domain every key is bound to, and the one decision here that cannot be taken back:",
            "changing it invalidates every registered key. Every page under this domain may ask the",
            "browser for the key, so no subdomain gets an application trusted less than this one.",
            "It must be public-url's host or a parent of it; startup refuses anything else."
        })
        @Explain(
                "Cannot be taken back: changing it invalidates every registered key, and every subdomain of it becomes a trusted sign-in surface.")
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
        @Comment("The compose service name and the API port; no TLS, since it never leaves the network.")
        @NoExplanationNeeded
        default String baseUrl() {
            return "http://steward-deployer:8081";
        }

        @Order(2)
        @Name("Token")
        @Key("token")
        @Comment("The shared secret steward-deployer gets as NORDTAL_STEWARD_DEPLOYER_TOKEN, from the environment.")
        @Explain(
                "The same secret steward-deployer expects as NORDTAL_STEWARD_DEPLOYER_TOKEN; empty leaves the recreate button unoffered.")
        default String token() {
            return "";
        }
    }

    /** A VAPID keypair, which proves to a push service which deployment sent a message. */
    @ConfigSpec
    interface WebPushSpec {

        @Order(1)
        @Name("Public key")
        @Key("public-key")
        @Comment("The public half, X509 and base64; not a secret, since it is every subscriber's applicationServerKey.")
        @NoExplanationNeeded
        default String publicKey() {
            return "";
        }

        @Order(2)
        @Name("Private key")
        @Key("private-key")
        @Comment({
            "The private half, PKCS8 and base64, which signs every push. It is marked @Secret because",
            "the name heuristic, which looks for secret, token or password, would miss it."
        })
        @Secret
        @NoExplanationNeeded
        default String privateKey() {
            return "";
        }

        @Order(3)
        @Name("Subject")
        @Key("subject")
        @Comment("Who a push service may contact about this identity: a mailto: address or an https:// URL.")
        @NoExplanationNeeded
        default String subject() {
            return "mailto:admin@nordtal.eu";
        }
    }

    /** The traffic light's thresholds, kept here rather than in the browser since the light also fires into Discord. */
    @ConfigSpec
    interface AlertSpec {

        @Order(1)
        @Name("Disk usage (percent)")
        @Key("disk-percent")
        @Comment("How full the disk may get before the start page says so; meant to fire long before anything stops.")
        @NoExplanationNeeded
        default int diskPercent() {
            return 85;
        }

        @Order(2)
        @Name("Memory usage (percent)")
        @Key("memory-percent")
        @Comment("The same for memory, as a share of the whole machine, since no container sets a limit.")
        @Explain("A share of the whole host's memory, since no container sets a limit of its own.")
        default int memoryPercent() {
            return 90;
        }

        @Order(3)
        @Name("Backup age (hours)")
        @Key("backup-age-hours")
        @Comment({
            "How old the newest finished backup may be before the light turns red. 36 hours leaves",
            "one missed night visible. It counts files on disk, not runs that reported success."
        })
        @Explain(
                "Counts files actually on disk, not runs that reported success, since a run once reported success having saved nothing.")
        default int backupAgeHours() {
            return 36;
        }
    }

    /** Where the identity display's Minecraft head comes from, a pure function of {@code mc_uuid} and this URL. */
    @ConfigSpec
    interface AvatarSpec {

        @Order(1)
        @Name("Minecraft head base URL")
        @Key("minecraft-head-base-url")
        @Comment({
            "A free third-party service, so a missing answer draws a placeholder and never blocks the",
            "page. Every render sends it the mc_uuid being looked at. The display inserts '/<uuid>'",
            "without hyphens before the query, so this ends at the path segment before it, optionally",
            "followed by a query such as '?scale=16', and never with a slash."
        })
        @Explain(
                "Every render sends this third-party service the mc_uuid being looked at, the one piece of player data an admin's page view lets out.")
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
        @Comment("The compose service name and the API port; no TLS, since it never leaves the network.")
        @NoExplanationNeeded
        default String baseUrl() {
            return "http://steward-worker:8082";
        }

        @Order(2)
        @Name("Token")
        @Key("token")
        @Comment("The shared secret steward-worker is given; when empty, every page says it cannot read containers.")
        @Explain("Empty means every page says it cannot read the containers, rather than drawing an empty table.")
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
        @Comment("The application's id, public since it is in the URL a browser is sent to.")
        @NoExplanationNeeded
        default String clientId() {
            return "";
        }

        @Order(2)
        @Name("Client secret")
        @Key("client-secret")
        @Comment("From the environment. Empty means nobody can sign in, and the sign-in page names what is missing.")
        @Explain(
                "Empty means nobody can sign in, and the sign-in page names the missing value rather than failing at Discord.")
        default String clientSecret() {
            return "";
        }

        @Order(3)
        @Name("Guild ID")
        @Key("guild-id")
        @Comment({
            "The guild whose roles are read, a deliberate second copy of the bot's access.yml value:",
            "this one decides who reaches the interface, the bot's who reaches the game."
        })
        @Explain(
                "A second, independent copy of the bot's guild id: this one decides who reaches the interface, the bot's who reaches the game.")
        default String guildId() {
            return "";
        }

        @Order(4)
        @Name("Bot token")
        @Key("bot-token")
        @Comment({
            "The bot's token, used only to read the names of the guild's roles and channels for the",
            "editor's pickers, and never sent anywhere else. Empty falls back to typed ids."
        })
        @Explain(
                "Used only to name roles and channels in the config editor; never to send, change a role, or show the token anywhere.")
        default String botToken() {
            return "";
        }
    }
}
