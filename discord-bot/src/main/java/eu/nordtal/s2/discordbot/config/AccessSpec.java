package eu.nordtal.s2.discordbot.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Protected;
import eu.nordtal.jcore.config.spec.annotation.Reload;
import java.util.List;

/**
 * The {@code access} group: the product and the guild.
 *
 * Every id defaults to empty and the bot refuses to start while one is.
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
    @Name("Tiers")
    @Key("tiers")
    @Comment({
        "What can be bought: a number of days and its price in cents per entry.",
        "More days must cost more, and no two entries may offer the same days.",
        "A tier is identified by its day count, so changing 'days' retires it.",
        "",
        "The list may not be empty. If you have emptied it, this is the shape:",
        "",
        "  tiers:",
        "  - days: 30",
        "    price-cents: 300",
        "  - days: 60",
        "    price-cents: 500"
    })
    @Explain(
            "What can be bought. Entries must be ordered by days ascending with price rising to match; changing 'days' on an entry retires that tier.")
    default List<TierSpec> tiers() {
        return DefaultTiers.LIST;
    }

    @Order(3)
    @Name("Donation (cents)")
    @Key("donation-cents")
    @Comment({
        "The optional surcharge that grants the permanent donor role, in cents.",
        "",
        "Money above the ordered total is a donation once it reaches this amount."
    })
    @Explain(
            "The extra amount that grants the donor role; a surplus of at least this much above the order is a donation.")
    default int donationCents() {
        return 500;
    }

    @Order(4)
    @Name("Roles")
    @Key("roles")
    @Comment({
        "Role ids that are not per-language, as strings, since a snowflake overflows a YAML",
        "integer. Each language's own role is on its entry under 'languages'."
    })
    @Explain("Role ids that are not specific to a language; a language's own role is on its entry under languages.")
    RolesSpec roles();

    @Order(5)
    @Name("Channels")
    @Key("channels")
    @Comment({
        "Channel ids that are not per-language. Snowflakes, as strings. The buy-access and",
        "account-link channels are on the 'languages' entries below, one pair per language."
    })
    @Explain(
            "Channel ids that are not specific to a language; a language's own channels are on its entry under languages.")
    ChannelsSpec channels();

    @Order(6)
    @Name("Languages")
    @Key("languages")
    @Comment({
        "Every language the network speaks. To add one: create its role and channels in",
        "Discord, add an entry, add <tag>.properties to every module's messages/, restart.",
        "",
        "'en' is mandatory as the fallback. Tags are unique, lower case, and the bundle",
        "file names; changing 'tag' on an entry retires that language.",
        "",
        "If you have emptied the list, this is the shape:",
        "",
        "  languages:",
        "  - tag: en",
        "    role: '000000000000000000'",
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

    @Order(7)
    @Name("Payment")
    @Key("payment")
    @Comment("The life cycle of a payment request, and how often the bot re-reads the seam.")
    @Explain("The life cycle of a payment request, and how often the bot re-reads the payment seam.")
    PaymentSpec payment();

    @Order(8)
    @Name("Reminder before expiry (days)")
    @Key("expiry-reminder-lead-days")
    @Comment("How many days before access runs out the reminder DM is sent.")
    @NoExplanationNeeded
    default int expiryReminderLeadDays() {
        return 3;
    }

    @Order(9)
    @Name("Role sync interval (minutes)")
    @Key("role-reconcile-interval-minutes")
    @Comment({
        "How often the access role is reconciled against the database. It walks only role",
        "holders and grant holders, so it can be frequent without being expensive."
    })
    @NoExplanationNeeded
    default int roleReconcileIntervalMinutes() {
        return 10;
    }

    @Order(10)
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

    /** One purchasable period: a number of days for a price. */
    @ConfigSpec
    interface TierSpec {

        @Order(1)
        @Name("Duration (days)")
        @Key("days")
        @Comment("How many days of access this buys. A day is exactly 24 hours.")
        @NoExplanationNeeded
        default int days() {
            return 30;
        }

        @Order(2)
        @Name("Price (cents)")
        @Key("price-cents")
        @Comment("What it costs, in cents. Integer cents everywhere; never a float.")
        @NoExplanationNeeded
        default int priceCents() {
            return 300;
        }
    }

    /** One language: its tag, the onboarding role that chooses it, and the channels that carry its messages. */
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
        @Name("Discord role")
        @Key("role")
        @Comment({
            "The role Discord's onboarding assigns for this language. The bot only reads it",
            "into discord_user.locale; no role at all means English."
        })
        @Explain("The Discord onboarding role that selects this language; no role at all means English.")
        default String role() {
            return "";
        }

        @Order(3)
        @Name("Contribution channel")
        @Key("contribution-channel")
        @Comment("Carries the buy-access message in this language, and its donation thank-yous.")
        @Explain("Carries the buy-access message in this language, and its donation thank-yous.")
        default String contributionChannel() {
            return "";
        }

        @Order(4)
        @Name("Link channel")
        @Key("link-channel")
        @Comment("Carries the account-link message in this language.")
        @Explain("Carries the account-link message in this language.")
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
        default String announcementChannel() {
            return "";
        }
    }

    /** Role ids the bot reads or writes. */
    @ConfigSpec
    interface RolesSpec {

        @Order(1)
        @Name("Access role")
        @Key("access")
        @Comment({
            "Bot-owned: it is added and removed to match the database, so granting it by",
            "hand holds only until the next reconcile. Use /grant-access."
        })
        @Explain("Bot-managed: granting it by hand only holds until the next reconcile. Use /grant-access instead.")
        default String access() {
            return "";
        }

        @Order(2)
        @Name("Donor role")
        @Key("donor")
        @Comment({"Granted on a donation and never taken away, so handing it out by hand is safe."})
        @Explain("Granted on a donation and never revoked, so it is safe to hand out manually in Discord.")
        default String donor() {
            return "";
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
            return "";
        }

        @Order(4)
        @Name("Admin ping role")
        @Key("admin-ping")
        @Comment({
            "Mentioned in the admin channel for entries that need a human. It grants no",
            "power and may be the same role as 'admin'."
        })
        @Explain("Only decides who is pinged in the admin channel; grants no power, and may be the same role as admin.")
        default String adminPing() {
            return "";
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
