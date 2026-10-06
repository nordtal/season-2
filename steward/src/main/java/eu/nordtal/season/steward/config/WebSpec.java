package eu.nordtal.season.steward.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import eu.nordtal.season.spec.annotation.Secret;

/**
 * The {@code web} group: how the web interface signs people in and what it pushes to them.
 *
 * Every secret arrives as an environment variable, so the file itself holds none.
 */
@ConfigSpec
public interface WebSpec {

    @Order(1)
    @Name("Port")
    @Key("port")
    @NoExplanationNeeded
    default int port() {
        return 8080;
    }

    @Order(2)
    @Name("Public URL")
    @Key("public-url")
    @Explain(
            "Pins both the Discord redirect URI and the WebAuthn origin, and must lie under webauthn.relying-party-id, which startup checks.")
    default String publicUrl() {
        return "https://steward.dev.nordtal.eu";
    }

    @Order(3)
    @Name("Discord")
    @Key("discord")
    @Explain(
            "The bot's own Discord application, reused. Discord only decides who may register a security key; webauthn below is a second, mandatory gate.")
    DiscordSpec discord();

    @Order(4)
    @Name("Session length (days)")
    @Key("session-days")
    @Explain(
            "Only defensible because a security key guards every dangerous action; without that, a stolen cookie is a month-long back door.")
    default int sessionDays() {
        return 30;
    }

    @Order(5)
    @Name("Avatars")
    @Key("avatars")
    @NoExplanationNeeded
    AvatarSpec avatars();

    @Order(6)
    @Name("Passkeys")
    @Key("webauthn")
    @NoExplanationNeeded
    WebAuthnSpec webauthn();

    @Order(7)
    @Name("Web push")
    @Key("web-push")
    @NoExplanationNeeded
    WebPushSpec webPush();

    /** Which domain a security key is registered against; the display name is fixed in code. */
    @ConfigSpec
    interface WebAuthnSpec {

        @Order(1)
        @Name("Relying party ID")
        @Key("relying-party-id")
        @Explain(
                "Cannot be taken back: changing it invalidates every registered key, and every subdomain of it becomes a trusted sign-in surface.")
        default String relyingPartyId() {
            return "nordtal.eu";
        }
    }

    /** A VAPID keypair, which proves to a push service which deployment sent a message. */
    @ConfigSpec
    interface WebPushSpec {

        @Order(1)
        @Name("Public key")
        @Key("public-key")
        @NoExplanationNeeded
        default String publicKey() {
            return "";
        }

        @Order(2)
        @Name("Private key")
        @Key("private-key")
        @Secret
        @NoExplanationNeeded
        default String privateKey() {
            return "";
        }

        @Order(3)
        @Name("Subject")
        @Key("subject")
        @NoExplanationNeeded
        default String subject() {
            return "mailto:admin@nordtal.eu";
        }
    }

    /** Where the identity display's Minecraft head comes from, a pure function of {@code mc_uuid} and this URL. */
    @ConfigSpec
    interface AvatarSpec {

        @Order(1)
        @Name("Minecraft head base URL")
        @Key("minecraft-head-base-url")
        @Explain(
                "Every render sends this third-party service the mc_uuid being looked at, the one piece of player data an admin's page view lets out.")
        default String minecraftHeadBaseUrl() {
            return "https://api.mineatar.io/face?scale=16";
        }
    }

    /** The Discord application and the guild whose roles decide who may sign in. */
    @ConfigSpec
    interface DiscordSpec {

        @Order(1)
        @Name("Client ID")
        @Key("client-id")
        @NoExplanationNeeded
        default String clientId() {
            return "";
        }

        @Order(2)
        @Name("Client secret")
        @Key("client-secret")
        @Explain(
                "Empty means nobody can sign in, and the sign-in page names the missing value rather than failing at Discord.")
        default String clientSecret() {
            return "";
        }

        @Order(3)
        @Name("Guild ID")
        @Key("guild-id")
        @Explain(
                "A second, independent copy of the bot's guild id: this one decides who reaches the interface, the bot's who reaches the game.")
        default String guildId() {
            return "";
        }

        @Order(4)
        @Name("Root's Discord ID")
        @Key("root-id")
        @Explain(
                "The one account that may become root while nobody is an admin, as after a fresh install or the restore of an early dump; empty means nobody can.")
        default String rootId() {
            return "";
        }
    }
}
