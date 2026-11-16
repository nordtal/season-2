package eu.nordtal.season.steward.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.settings.rule.Between;
import eu.nordtal.season.settings.rule.Positive;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import eu.nordtal.season.spec.annotation.Secret;

/** Holds the {@code web} group. Its secrets are environment variables. */
@ConfigSpec
public interface WebSpec {

    @Order(1)
    @Name("Port")
    @Key("port")
    @Positive
    @NoExplanationNeeded
    default int port() {
        return 8080;
    }

    @Order(2)
    @Name("Public URL")
    @Key("public-url")
    @Explain("The URL’s host must match the relying party ID or one of its subdomains.")
    default String publicUrl() {
        return "https://steward.nordtal.eu";
    }

    @Order(3)
    @Name("Discord")
    @Key("discord")
    @NoExplanationNeeded
    DiscordSpec discord();

    @Order(4)
    @Name("Session length (days)")
    @Key("session-days")
    // The ceiling keeps Max-Age within an int, and no session outlasts a season.
    @Between(least = 1, most = 365)
    @NoExplanationNeeded
    default int sessionDays() {
        return 30;
    }

    @Order(5)
    @Name("Avatars")
    @Key("avatars")
    @NoExplanationNeeded
    AvatarSpec avatars();

    @Order(6)
    @Name("Security keys")
    @Key("webauthn")
    @NoExplanationNeeded
    WebAuthnSpec webauthn();

    @Order(7)
    @Name("Web push")
    @Key("web-push")
    @NoExplanationNeeded
    WebPushSpec webPush();

    @Order(8)
    @Name("Authenticator app key")
    @Key("totp-key")
    @Secret
    @Explain("Use 64 hex digits. Changing this key disables existing authenticator apps.")
    default String totpKey() {
        return "";
    }

    /** The display name is fixed in code. */
    @ConfigSpec
    interface WebAuthnSpec {

        @Order(1)
        @Name("Relying party ID")
        @Key("relying-party-id")
        @Explain("Changing this ID invalidates all registered security keys.")
        default String relyingPartyId() {
            return "nordtal.eu";
        }
    }

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

    /** Sets where the Minecraft head comes from, as a function of {@code mc_uuid} and this URL. */
    @ConfigSpec
    interface AvatarSpec {

        @Order(1)
        @Name("Minecraft head base URL")
        @Key("minecraft-head-base-url")
        @Explain("Loading a Minecraft head sends that player’s UUID to this third-party service.")
        default String minecraftHeadBaseUrl() {
            return "https://api.mineatar.io/face?scale=16";
        }
    }

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
        @Explain("An empty value disables sign-in.")
        default String clientSecret() {
            return "";
        }

        @Order(3)
        @Name("Server ID")
        @Key("guild-id")
        @Explain("Uses this Discord server to decide who may sign in, independently of the bot’s server.")
        default String guildId() {
            return "";
        }

        @Order(4)
        @Name("Root's Discord ID")
        @Key("root-id")
        @Explain("This user can become root when no admins exist. Leave empty to prevent root signup.")
        @Refers(value = Refers.To.DISCORD_USER, optional = true)
        default String rootId() {
            return "";
        }
    }
}
