package eu.nordtal.season.discordbot.config;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import eu.nordtal.season.spec.annotation.Protected;
import eu.nordtal.season.spec.annotation.Reload;
import java.util.List;

/**
 * The {@code access} group: the guild, its roles and channels, and the life of a purchase.
 *
 * The price list is the network's. Roles are named, channels are ids; the guild is required.
 */
@ConfigSpec
public interface AccessSpec {

    /** The guild the bot manages; every other guild it is in is ignored. */
    @Order(1)
    @Name("Guild ID")
    @Key("guild-id")
    @NoExplanationNeeded
    default String guildId() {
        return "";
    }

    /** Each role is found by its name, created when missing, and followed through a rename in Discord. */
    @Order(2)
    @Name("Roles")
    @Key("role-names")
    @NoExplanationNeeded
    RoleNamesSpec roleNames();

    /** Channel ids that are not specific to a language; a language's own are on its entry under languages. */
    @Order(3)
    @Name("Channels")
    @Key("channels")
    @NoExplanationNeeded
    ChannelsSpec channels();

    /** A missing translation falls back to the 'en' entry. */
    @Order(4)
    @Name("Languages")
    @Key("languages")
    // BotSettings#validateLanguages and steward's schema reader both read this annotation.
    @Protected(field = "tag", value = Locales.DEFAULT_TAG)
    @NoExplanationNeeded
    default List<LanguageSpec> languages() {
        return DefaultLanguages.LIST;
    }

    @Order(5)
    @Name("Payment")
    @Key("payment")
    @NoExplanationNeeded
    PaymentSpec payment();

    @Order(6)
    @Name("Reminder before expiry (days)")
    @Key("expiry-reminder-lead-days")
    @NoExplanationNeeded
    default int expiryReminderLeadDays() {
        return 3;
    }

    @Order(7)
    @Name("Role sync interval (minutes)")
    @Key("role-reconcile-interval-minutes")
    @NoExplanationNeeded
    default int roleReconcileIntervalMinutes() {
        return 10;
    }

    /** The bundle file name in every module's messages directory and the value stored as a player's locale. */
    @Order(8)
    @Name("Link code attempts per hour")
    @Key("link-code-attempts-per-hour")
    @Explain("Raising it weakens the link code against guessing.")
    default int linkCodeAttemptsPerHour() {
        return 5;
    }

    @Reload
    void reload();

    /** One language: its tag, the role that chooses it, and the channels that carry its messages. */
    @ConfigSpec
    interface LanguageSpec {

        /** Empty is the language's own name, such as Deutsch. */
        @Order(1)
        @Name("Tag")
        @Key("tag")
        @NoExplanationNeeded
        default String tag() {
            return "";
        }

        /** Carries the buy-access message in this language, and its donation thank-yous. */
        @Order(2)
        @Name("Role name")
        @Key("role-name")
        @NoExplanationNeeded
        default String roleName() {
            return "";
        }

        /** Carries the account-link message in this language. */
        @Order(3)
        @Name("Contribution channel")
        @Key("contribution-channel")
        @NoExplanationNeeded
        @Refers(Refers.To.DISCORD_CHANNEL)
        default String contributionChannel() {
            return "";
        }

        /**
         * Carries the Hunger Games registration message.
         *
         * Separate from the contribution channel, because access is not required to play.
         */
        @Order(4)
        @Name("Link channel")
        @Key("link-channel")
        @NoExplanationNeeded
        @Refers(Refers.To.DISCORD_CHANNEL)
        default String linkChannel() {
            return "";
        }

        /** The bot only renames it and never posts in it. Empty means none. */
        @Order(5)
        @Name("Hunger Games channel")
        @Key("hunger-games-channel")
        @NoExplanationNeeded
        @Refers(Refers.To.DISCORD_CHANNEL)
        default String hungerGamesChannel() {
            return "";
        }

        /** The bot posts milestones and season announcements here for this language. */
        @Order(6)
        @Name("Status channel")
        @Key("status-channel")
        @NoExplanationNeeded
        @Refers(value = Refers.To.DISCORD_CHANNEL, optional = true)
        default String statusChannel() {
            return "";
        }

        /** Managed by the bot: a hand-granted role is undone at the next reconcile, so /grant-access is the way. */
        @Order(7)
        @Name("Announcement channel")
        @Key("announcement-channel")
        @NoExplanationNeeded
        @Refers(value = Refers.To.DISCORD_CHANNEL, optional = true)
        default String announcementChannel() {
            return "";
        }
    }

    /** The names of the roles the bot keeps that are not per-language. */
    @ConfigSpec
    interface RoleNamesSpec {

        /** Granted on a donation and never revoked, so handing it out in Discord is safe. */
        @Order(1)
        @Name("Access role")
        @Key("access")
        @NoExplanationNeeded
        default String access() {
            return "Access";
        }

        /** Follows the admins decided in Steward: the bot adds and removes it and never reads it as a permission. */
        @Order(2)
        @Name("Donor role")
        @Key("donor")
        @NoExplanationNeeded
        default String donor() {
            return "Donor";
        }

        /**
         * Receives everything needing a human.
         *
         * That is unmatchable or expired payments, failed DMs, role errors, and every link or unlink.
         */
        @Order(3)
        @Name("Admin role")
        @Key("admin")
        @NoExplanationNeeded
        default String admin() {
            return "Admin";
        }
    }

    /** Channel ids the bot writes to that are not per-language. */
    @ConfigSpec
    interface ChannelsSpec {

        /** Raising it takes the four-character link code's brute-force resistance from decades toward weeks. */
        @Order(1)
        @Name("Admin channel")
        @Key("admin")
        @NoExplanationNeeded
        @Refers(Refers.To.DISCORD_CHANNEL)
        default String admin() {
            return "";
        }
    }

    /** The life cycle of a payment request. */
    @ConfigSpec
    interface PaymentSpec {

        /** A late payment then needs manual handling in the admin channel. */
        @Order(2)
        @Name("Request lifetime (hours)")
        @Key("request-ttl-hours")
        @NoExplanationNeeded
        default int requestTtlHours() {
            return 24;
        }
    }
}
