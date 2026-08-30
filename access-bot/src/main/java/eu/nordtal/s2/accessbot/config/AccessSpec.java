package eu.nordtal.s2.accessbot.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Reload;

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
        "  tiers.short-price-cents -> NORDTAL_ACCESS_TIERS_SHORT_PRICE_CENTS",
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
            "What can be bought. Three fixed slots - short, medium and long - each with its own",
            "number of days and its own price. The names are labels for this file only; nothing",
            "user-visible uses them, and the embed is rendered from the numbers below."
    })
    TiersSpec tiers();

    @Order(3)
    @Key("donation-cents")
    @Comment({
            "The optional surcharge that grants the permanent donor role, in cents.",
            "Paying a tier price plus this amount counts as a donation - see the",
            "'pay what you get' rule in docs/access-system.md."
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
     * The three purchasable periods, as six flat settings rather than three nested objects.
     * <p>
     * A nested {@code TierSpec} reused three times would be tidier to read and would write a
     * <b>wrong</b> defaults file: one interface has one set of defaults, so a fresh
     * {@code access.yml} would offer 30 days three times. Six keys with six correct defaults are
     * worth the flatness. Three interfaces with one set of defaults each would also work and is
     * three times the boilerplate for the same six numbers.
     * </p>
     * <p>
     * The number of tiers is fixed at three by this shape. That is the product as agreed
     * (30/60/90); a fourth tier is a change here and in {@code Tiers}, not a config edit.
     * </p>
     */
    @ConfigSpec
    interface TiersSpec {

        @Order(1)
        @Key("short-days")
        @Comment("Days of access in the smallest tier. A day is exactly 24 hours.")
        default int shortDays() {
            return 30;
        }

        @Order(2)
        @Key("short-price-cents")
        @Comment("What it costs, in cents. Integer cents everywhere; never a float.")
        default int shortPriceCents() {
            return 300;
        }

        @Order(3)
        @Key("medium-days")
        default int mediumDays() {
            return 60;
        }

        @Order(4)
        @Key("medium-price-cents")
        default int mediumPriceCents() {
            return 500;
        }

        @Order(5)
        @Key("long-days")
        default int longDays() {
            return 90;
        }

        @Order(6)
        @Key("long-price-cents")
        default int longPriceCents() {
            return 700;
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
                "ISO-8601, UTC, e.g. 2026-09-01T00:00:00Z.",
                "",
                "This is not an optimisation. bunq returns the last 50 payments on the account",
                "whatever the database knows, so the first run against an empty database would",
                "otherwise book up to 50 historical payments - grants, roles, DMs and public",
                "thank-yous included. Set it to the moment season 2 opens."
        })
        default String watermark() {
            return "2026-09-01T00:00:00Z";
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
