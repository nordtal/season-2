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

    @Order(1)
    @Name("Guild ID")
    @Key("guild-id")
    @Explain("The one Discord guild the bot manages; every other guild it is in is ignored.")
    default String guildId() {
        return "";
    }

    @Order(2)
    @Name("Roles")
    @Key("role-names")
    @Explain(
            "The bot takes the role of exactly this name, or creates it, and then follows it even when it is renamed in Discord.")
    RoleNamesSpec roleNames();

    @Order(3)
    @Name("Channels")
    @Key("channels")
    @Explain(
            "Channel ids that are not specific to a language; a language's own channels are on its entry under languages.")
    ChannelsSpec channels();

    @Order(4)
    @Name("Languages")
    @Key("languages")
    // BotSettings#validateLanguages and steward's schema reader both read this annotation.
    @Protected(field = "tag", value = Locales.DEFAULT_TAG)
    @Explain(
            "One entry for each language the network speaks, by its tag, and no other. The 'en' entry cannot be removed: a missing translation falls back to it.")
    default List<LanguageSpec> languages() {
        return DefaultLanguages.LIST;
    }

    @Order(5)
    @Name("Payment")
    @Key("payment")
    @Explain("The life cycle of a payment request, and how often the bot re-reads the payment seam.")
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

    @Order(8)
    @Name("Link code attempts per hour")
    @Key("link-code-attempts-per-hour")
    @Explain(
            "A security limit, not a comfort setting: raising it weakens the four-character link code's brute-force resistance from decades toward weeks.")
    default int linkCodeAttemptsPerHour() {
        return 5;
    }

    @Reload
    void reload();

    /** One language: its tag, the role that chooses it, and the channels that carry its messages. */
    @ConfigSpec
    interface LanguageSpec {

        @Order(1)
        @Name("Tag")
        @Key("tag")
        @Explain(
                "Lower case; the bundle file name in every module's messages/ directory and the value stored for a player's locale.")
        default String tag() {
            return "";
        }

        @Order(2)
        @Name("Role name")
        @Key("role-name")
        @Explain("The role that chooses this language. Empty is the language's own name, such as Deutsch.")
        default String roleName() {
            return "";
        }

        @Order(3)
        @Name("Contribution channel")
        @Key("contribution-channel")
        @Explain("Carries the buy-access message in this language, and its donation thank-yous.")
        @Refers(Refers.To.DISCORD_CHANNEL)
        default String contributionChannel() {
            return "";
        }

        @Order(4)
        @Name("Link channel")
        @Key("link-channel")
        @Explain("Carries the account-link message in this language.")
        @Refers(Refers.To.DISCORD_CHANNEL)
        default String linkChannel() {
            return "";
        }

        @Order(5)
        @Name("Hunger Games channel")
        @Key("hunger-games-channel")
        @Explain(
                "Carries the hunger games registration message; separate from contribution-channel, since access is not required to play.")
        @Refers(Refers.To.DISCORD_CHANNEL)
        default String hungerGamesChannel() {
            return "";
        }

        @Order(6)
        @Name("Status channel")
        @Key("status-channel")
        @Explain(
                "Optional: a channel the bot renames (never posts in) to show this language's current status. Empty means none.")
        @Refers(value = Refers.To.DISCORD_CHANNEL, optional = true)
        default String statusChannel() {
            return "";
        }

        @Order(7)
        @Name("Announcement channel")
        @Key("announcement-channel")
        @Explain(
                "Optional: a channel the bot posts milestones and season announcements into for this language. Empty means none.")
        @Refers(value = Refers.To.DISCORD_CHANNEL, optional = true)
        default String announcementChannel() {
            return "";
        }
    }

    /** The names of the roles the bot keeps that are not per-language. */
    @ConfigSpec
    interface RoleNamesSpec {

        @Order(1)
        @Name("Access role")
        @Key("access")
        @Explain("Bot-managed: granting it by hand only holds until the next reconcile. Use /grant-access instead.")
        default String access() {
            return "Access";
        }

        @Order(2)
        @Name("Donor role")
        @Key("donor")
        @Explain("Granted on a donation and never revoked, so it is safe to hand out manually in Discord.")
        default String donor() {
            return "Donor";
        }

        @Order(3)
        @Name("Admin role")
        @Key("admin")
        @Explain(
                "Follows the admins decided in Steward: the bot adds and removes it, and never reads it as a permission.")
        default String admin() {
            return "Admin";
        }
    }

    /** Channel ids the bot writes to that are not per-language. */
    @ConfigSpec
    interface ChannelsSpec {

        @Order(1)
        @Name("Admin channel")
        @Key("admin")
        @Explain(
                "Everything needing a human: unmatchable or expired payments, failed DMs, role errors, and every link or unlink.")
        @Refers(Refers.To.DISCORD_CHANNEL)
        default String admin() {
            return "";
        }
    }

    /** The life cycle of a payment request. */
    @ConfigSpec
    interface PaymentSpec {

        @Order(2)
        @Name("Request lifetime (hours)")
        @Key("request-ttl-hours")
        @Explain("Past this, the bunq tab is cancelled and a late payment needs manual handling in the admin channel.")
        default int requestTtlHours() {
            return 24;
        }
    }
}
