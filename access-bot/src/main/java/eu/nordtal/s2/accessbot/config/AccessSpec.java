package eu.nordtal.s2.accessbot.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Reload;

import java.util.List;

/**
 * {@code config/access.yml} - everything about the product, the guild and the poll loop.
 * <p>
 * <b>Nothing here is an enum.</b> Season 1 hard-coded four contribution tiers, their prices and
 * their role ids into {@code ContributionTier}, so a price change was a release. Prices, day
 * counts, role ids and channel ids all live in this file; the code only knows that there are
 * tiers, that a tier costs money and buys days, and that a donation is a surcharge.
 * </p>
 * <p>
 * <b>Every id defaults to empty and the bot refuses to start while one is.</b> The season 1 bot
 * shipped real channel and role ids as defaults, which meant a config the loader could not read
 * fell back to writing into somebody's production channel. An empty default cannot do that: the
 * bot stops with a message naming the setting.
 * </p>
 *
 * @see Configs#access()
 */
@ConfigSpec(header = {
        "-------------------------------------------------------------------",
        "  access-bot - the product, the guild and the poll loop",
        "-------------------------------------------------------------------",
        "Every setting here can be overridden with an environment variable",
        "named NORDTAL_ACCESS_<PATH>, with '.' and '-' both becoming '_':",
        "",
        "  donation-cents  ->  NORDTAL_ACCESS_DONATION_CENTS",
        "",
        "The environment wins over this file and is never written back into",
        "it. A setting this file does not declare stops the bot from starting",
        "rather than being deleted.",
        "",
        "The role and channel ids are EMPTY by default and the bot will not",
        "start until they are filled in. That is deliberate: a default id",
        "would be a real channel in somebody's guild."
})
public interface AccessSpec {

    @Order(1)
    @Key("guild-id")
    @Comment({
            "The one guild the bot manages. Roles are reconciled and members are",
            "resolved against it; the bot ignores every other guild it is in."
    })
    default String guildId() {
        return "";
    }

    @Order(2)
    @Key("tiers")
    @Comment({
            "What can be bought. A list, so a fourth tier is an edit here and not a release.",
            "",
            "Each entry is a number of days and what it costs in cents. They must be ordered:",
            "more days must cost more, and no two entries may offer the same number of days.",
            "A tier is identified by its day count everywhere else - that is what a purchase",
            "button carries - so changing 'days' on an existing entry retires that tier.",
            "",
            "The list may not be empty. If you have emptied it, this is the shape:",
            "",
            "  tiers:",
            "  - days: 30",
            "    price-cents: 300",
            "  - days: 60",
            "    price-cents: 500"
    })
    default List<TierSpec> tiers() {
        return DefaultTiers.LIST;
    }

    @Order(3)
    @Key("donation-cents")
    @Comment({
            "The optional surcharge that grants the permanent donor role, in cents.",
            "",
            "It is also how a payment larger than the order is read: money left over above the",
            "ordered total is a donation once it reaches this amount, and is otherwise ignored.",
            "See the settlement rule in docs/access-system.md."
    })
    default int donationCents() {
        return 500;
    }

    @Order(4)
    @Key("roles")
    @Comment("Role ids. Snowflakes, as strings - a snowflake does not fit in a YAML integer safely.")
    RolesSpec roles();

    @Order(5)
    @Key("channels")
    @Comment("Channel ids. Snowflakes, as strings.")
    ChannelsSpec channels();

    @Order(6)
    @Key("payment")
    @Comment("The bunq poll loop and the life cycle of a payment request.")
    PaymentSpec payment();

    @Order(7)
    @Key("link-code-ttl-minutes")
    @Comment({
            "How long a link code shown on the Minecraft login screen stays valid.",
            "Stage C issues the codes; the value lives here because the bot owns the",
            "sweep that deletes expired ones."
    })
    default int linkCodeTtlMinutes() {
        return 10;
    }

    @Order(8)
    @Key("expiry-reminder-lead-days")
    @Comment("How many days before access runs out the reminder DM is sent.")
    default int expiryReminderLeadDays() {
        return 3;
    }

    @Order(9)
    @Key("role-reconcile-interval-minutes")
    @Comment({
            "How often the access role is reconciled against the database.",
            "This walks the members who hold the role plus the users who hold a grant - it is",
            "not a full member scan, so it can be frequent without being expensive."
    })
    default int roleReconcileIntervalMinutes() {
        return 10;
    }

    @Reload
    void reload();

    /**
     * One purchasable period: a number of days for a price.
     * <p>
     * A list of these rather than a fixed set of keys, because the owner has to be able to add a
     * fourth tier without a release. jcore can carry a non-empty default for a list of nested
     * specs - see {@link DefaultTiers} - so a fresh {@code access.yml} still ships with the agreed
     * 3/5/7 EUR price list rather than an empty list nobody can buy from.
     * </p>
     */
    @ConfigSpec
    interface TierSpec {

        @Order(1)
        @Key("days")
        @Comment("How many days of access this buys. A day is exactly 24 hours.")
        default int days() {
            return 30;
        }

        @Order(2)
        @Key("price-cents")
        @Comment("What it costs, in cents. Integer cents everywhere; never a float.")
        default int priceCents() {
            return 300;
        }
    }

    /** Role ids the bot reads or writes. */
    @ConfigSpec
    interface RolesSpec {

        @Order(1)
        @Key("access")
        @Comment({
                "Strictly bot-owned. It is added and removed to match the database, so granting",
                "it by hand holds only until the next reconcile. /grant-access is the way."
        })
        default String access() {
            return "";
        }

        @Order(2)
        @Key("donor")
        @Comment({
                "Granted on a donation and never taken away, by the bot or by the reconcile.",
                "That is what makes handing it out by hand in Discord's role UI safe."
        })
        default String donor() {
            return "";
        }

        @Order(3)
        @Key("german")
        @Comment({
                "The German language role from Discord's own onboarding. The bot never assigns",
                "it - it only mirrors it into discord_user.locale."
        })
        default String german() {
            return "";
        }

        @Order(4)
        @Key("english")
        @Comment("The English language role from onboarding. Also read-only for the bot.")
        default String english() {
            return "";
        }

        @Order(5)
        @Key("admin-ping")
        @Comment({
                "Mentioned in the admin channel for entries that need a human.",
                "Routine audit entries are written without a mention."
        })
        default String adminPing() {
            return "";
        }
    }

    /** Channel ids the bot writes to. */
    @ConfigSpec
    interface ChannelsSpec {

        @Order(1)
        @Key("contribution-en")
        @Comment("Carries the English buy-access message and its public donation thank-yous.")
        default String contributionEn() {
            return "";
        }

        @Order(2)
        @Key("contribution-de")
        @Comment("The German one.")
        default String contributionDe() {
            return "";
        }

        @Order(3)
        @Key("link-en")
        @Comment("Carries the English account-link message. Stage C fills the button in.")
        default String linkEn() {
            return "";
        }

        @Order(4)
        @Key("link-de")
        @Comment("The German one.")
        default String linkDe() {
            return "";
        }

        @Order(5)
        @Key("admin")
        @Comment({
                "Everything a human may need to act on: unmatchable payments, payments on an",
                "expired reference, failed DMs, role errors, and every link and unlink."
        })
        default String admin() {
            return "";
        }
    }

    /** The bunq poll loop and the life cycle of a payment request. */
    @ConfigSpec
    interface PaymentSpec {

        @Order(1)
        @Key("poll-interval-seconds")
        @Comment("How often bunq is asked about open tabs and recent payments.")
        default int pollIntervalSeconds() {
            return 30;
        }

        @Order(2)
        @Key("request-ttl-hours")
        @Comment({
                "How long an unpaid request stays open. Past this the bunq tab is cancelled and",
                "the request goes to EXPIRED; a payment arriving afterwards is never booked",
                "automatically - it goes to the admin channel."
        })
        default int requestTtlHours() {
            return 24;
        }

        @Order(3)
        @Key("watermark")
        @Comment({
                "Payments created before this instant are ignored, completely and forever.",
                "",
                "LEAVE THIS EMPTY. On its first start the bot stamps the current instant into the",
                "database and uses that from then on, so the cut-off is the moment this bot first",
                "ran rather than a date somebody guessed. The stored value is written once and",
                "never rewritten.",
                "",
                "Set it only to deliberately choose a different cut-off; ISO-8601, UTC, e.g.",
                "2026-09-01T00:00:00Z. A value here overrides the stored one without replacing it,",
                "so emptying this again falls back to the original first-start instant.",
                "",
                "The cut-off is not an optimisation: bunq returns the last 50 payments on the",
                "account whatever the database knows, so without one the first poll would book up",
                "to 50 historical payments - grants, roles, DMs and public thank-yous included."
        })
        default String watermark() {
            return "";
        }

        @Order(4)
        @Key("recent-payment-count")
        @Comment({
                "How many recent payments the fallback reference scan looks at per poll.",
                "The primary match path is the tab's own result inquiries; this only catches",
                "money that reached the account outside a tab."
        })
        default int recentPaymentCount() {
            return 50;
        }
    }
}
