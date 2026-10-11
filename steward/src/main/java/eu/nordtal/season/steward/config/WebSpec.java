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

    /** Pins both the Discord redirect URI and the WebAuthn origin; startup checks the constraint. */
    @Order(2)
    @Name("Public URL")
    @Key("public-url")
    @Explain("Must lie under the relying party ID.")
    default String publicUrl() {
        return "https://steward.dev.nordtal.eu";
    }

    /**
     * The bot's own Discord application, reused.
     *
     * Discord only decides who may register a security key; webauthn is a second, mandatory gate.
     */
    @Order(3)
    @Name("Discord")
    @Key("discord")
    @NoExplanationNeeded
    DiscordSpec discord();

    /** Defensible only because a security key guards every dangerous action. */
    @Order(4)
    @Name("Session length (days)")
    @Key("session-days")
    @Explain("A stolen cookie works this long.")
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

        /** Every subdomain of it becomes a trusted sign-in surface, so it cannot be taken back. */
        @Order(1)
        @Name("Relying party ID")
        @Key("relying-party-id")
        @Explain("Changing it invalidates every registered key.")
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

        /** It is the one piece of player data an admin's page view lets out. */
        @Order(1)
        @Name("Minecraft head base URL")
        @Key("minecraft-head-base-url")
        @Explain("Every render sends the looked-at mc_uuid to this third-party service.")
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

        /** The sign-in page names the missing value rather than failing at Discord. */
        @Order(2)
        @Name("Client secret")
        @Key("client-secret")
        @Explain("Empty means nobody can sign in.")
        default String clientSecret() {
            return "";
        }

        /** This one decides who reaches the interface, the bot's who reaches the game. */
        @Order(3)
        @Name("Guild ID")
        @Key("guild-id")
        @Explain("Independent of the bot's guild id.")
        default String guildId() {
            return "";
        }

        /** As after a fresh install or the restore of an early dump. */
        @Order(4)
        @Name("Root's Discord ID")
        @Key("root-id")
        @Explain("The account that may become root while nobody is an admin. Empty means nobody.")
        default String rootId() {
            return "";
        }
    }
}
