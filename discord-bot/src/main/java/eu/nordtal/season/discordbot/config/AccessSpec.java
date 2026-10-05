package eu.nordtal.season.discordbot.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.Comment;
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
    @Comment({
        "The one guild the bot manages. Roles are reconciled and members are",
        "resolved against it; the bot ignores every other guild it is in."
    })
    @Explain("The one Discord guild the bot manages; every other guild it is in is ignored.")
    default String guildId() {
        return "";
    }

    @Order(2)
    @Name("Roles")
    @Key("role-names")
    @Comment({
        "The names of the roles that are not per-language. The bot takes the role of",
        "exactly this name, or creates it, and follows it by id from then on, so renaming",
        "it in Discord is fine. A language's own role is on its entry under 'languages'."
    })
    @Explain(
            "The bot takes the role of exactly this name, or creates it, and then follows it even when it is renamed in Discord.")
    RoleNamesSpec roleNames();

    @Order(3)
    @Name("Channels")
    @Key("channels")
    @Comment({
        "Channel ids that are not per-language. Snowflakes, as strings. The buy-access and",
        "account-link channels are on the 'languages' entries below, one pair per language."
    })
    @Explain(
            "Channel ids that are not specific to a language; a language's own channels are on its entry under languages.")
    ChannelsSpec channels();

    @Order(4)
    @Name("Languages")
    @Key("languages")
    @Comment({
        "Every language the network speaks. To add one: create its channels in Discord,",
        "add an entry, add <tag>.properties to every module's messages/, restart.",
        "",
        "'en' is mandatory as the fallback. Tags are unique, lower case, and the bundle",
        "file names; changing 'tag' on an entry retires that language.",
        "",
        "If you have emptied the list, this is the shape:",
        "",
        "  languages:",
        "  - tag: en",
        "    role-name: English",
        "    contribution-channel: '000000000000000000'",
        "    link-channel: '000000000000000000'",
        "    hunger-games-channel: '000000000000000000'"
    })
    // BotSettings#validateLanguages and steward's schema reader both read this annotation.
    @Protected(field = "tag", value = Languages.FALLBACK_TAG)
    @Explain(
            "Every language the network speaks. The 'en' entry cannot be removed: a missing translation falls back to it.")
    default List<LanguageSpec> languages() {
        return DefaultLanguages.LIST;
    }

    @Order(5)
    @Name("Payment")
    @Key("payment")
    @Comment("The life cycle of a payment request, and how often the bot re-reads the seam.")
    @Explain("The life cycle of a payment request, and how often the bot re-reads the payment seam.")
    PaymentSpec payment();

    @Order(6)
    @Name("Reminder before expiry (days)")
    @Key("expiry-reminder-lead-days")
    @Comment("How many days before access runs out the reminder DM is sent.")
    @NoExplanationNeeded
    default int expiryReminderLeadDays() {
        return 3;
    }

    @Order(7)
    @Name("Role sync interval (minutes)")
    @Key("role-reconcile-interval-minutes")
    @Comment({
        "How often the roles are reconciled: the access role against the database, and",
        "each member's language, region and lock roles against one another. Both change",
        "only what differs, so it can be frequent without being expensive."
    })
    @NoExplanationNeeded
    default int roleReconcileIntervalMinutes() {
        return 10;
    }

    @Order(8)
    @Name("Link code attempts per hour")
    @Key("link-code-attempts-per-hour")
    @Comment({
        "How many codes that matched nothing one Discord account may submit per hour.",
        "",
        "A SECURITY LIMIT: a link code is four characters from 31 symbols (923 521 codes).",
        "Five guesses an hour make guessing one take decades; five hundred, weeks. Raise",
        "it a little at most. The counter lives in memory and resets on restart."
    })
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
        @Comment({
            "The language tag, lower case. It is the bundle file name in every module's",
            "messages/ directory and the value stored in discord_user.locale.",
            "'en' is mandatory: it is what a missing translation falls back to."
        })
        @Explain(
                "Lower case; the bundle file name in every module's messages/ directory and the value stored for a player's locale.")
        default String tag() {
            return "";
        }

        @Order(2)
        @Name("Role name")
        @Key("role-name")
        @Comment({
            "The name of the role that chooses this language; empty is the language's own",
            "name, such as Deutsch. The bot takes or creates it and mirrors it into the",
            "player's language."
        })
        @Explain("The role that chooses this language. Empty is the language's own name, such as Deutsch.")
        default String roleName() {
            return "";
        }

        @Order(3)
        @Name("Contribution channel")
        @Key("contribution-channel")
        @Comment("Carries the buy-access message in this language, and its donation thank-yous.")
        @Explain("Carries the buy-access message in this language, and its donation thank-yous.")
        @Refers(Refers.To.DISCORD_CHANNEL)
        default String contributionChannel() {
            return "";
        }

        @Order(4)
        @Name("Link channel")
        @Key("link-channel")
        @Comment("Carries the account-link message in this language.")
        @Explain("Carries the account-link message in this language.")
        @Refers(Refers.To.DISCORD_CHANNEL)
        default String linkChannel() {
            return "";
        }

        @Order(5)
        @Name("Hunger Games channel")
        @Key("hunger-games-channel")
        @Comment({
            "Carries the hunger games Register message in this language. It is separate from",
            "contribution-channel because access is not required to play."
        })
        @Explain(
                "Carries the hunger games registration message; separate from contribution-channel, since access is not required to play.")
        @Refers(Refers.To.DISCORD_CHANNEL)
        default String hungerGamesChannel() {
            return "";
        }

        @Order(6)
        @Name("Status channel")
        @Key("status-channel")
        @Comment({
            "A channel whose NAME the bot sets to this language's status line; it never posts",
            "in it. Usually a voice channel. OPTIONAL: empty means no status channel.",
            "",
            "Discord allows two renames per ten minutes per channel, so the bot renames at",
            "most every six minutes and only when the text changed."
        })
        @Explain(
                "Optional: a channel the bot renames (never posts in) to show this language's current status. Empty means none.")
        @Refers(value = Refers.To.DISCORD_CHANNEL, optional = true)
        default String statusChannel() {
            return "";
        }

        @Order(7)
        @Name("Announcement channel")
        @Key("announcement-channel")
        @Comment({
            "The channel this language's announcements are posted into: finished milestones",
            "and season phase changes, worded by the server that sent them.",
            "OPTIONAL: empty means this language gets no announcements."
        })
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
        @Comment({
            "Bot-owned: it is added and removed to match the database, so granting it by",
            "hand holds only until the next reconcile. Use /grant-access."
        })
        @Explain("Bot-managed: granting it by hand only holds until the next reconcile. Use /grant-access instead.")
        default String access() {
            return "Access";
        }

        @Order(2)
        @Name("Donor role")
        @Key("donor")
        @Comment({"Granted on a donation and never taken away, so handing it out by hand is safe."})
        @Explain("Granted on a donation and never revoked, so it is safe to hand out manually in Discord.")
        default String donor() {
            return "Donor";
        }

        @Order(3)
        @Name("Admin role")
        @Key("admin")
        @Comment({
            "The role every admin carries. Admins are decided in Steward's Users page; the",
            "bot adds this role to every admin and removes it from everybody else.",
            "The bot's own role must sit above it in the guild's role list."
        })
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
        @Comment({
            "Everything a human may need to act on: unmatchable payments, payments on an",
            "expired reference, failed DMs, role errors, and every link and unlink."
        })
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
        @Comment({
            "How long an unpaid request stays open. Past this the bunq tab is cancelled and a",
            "late payment goes to the admin channel instead of being booked."
        })
        @Explain("Past this, the bunq tab is cancelled and a late payment needs manual handling in the admin channel.")
        default int requestTtlHours() {
            return 24;
        }
    }
}
