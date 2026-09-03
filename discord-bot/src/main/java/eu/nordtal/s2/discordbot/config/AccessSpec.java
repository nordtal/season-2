package eu.nordtal.s2.discordbot.config;

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
    @Comment({
            "Role ids that are not per-language. Snowflakes, as strings - a snowflake does not fit",
            "in a YAML integer safely. Each language's own role is on its entry under 'languages'."
    })
    RolesSpec roles();

    @Order(5)
    @Key("channels")
    @Comment({
            "Channel ids that are not per-language. Snowflakes, as strings. The buy-access and",
            "account-link channels are on the 'languages' entries below, one pair per language."
    })
    ChannelsSpec channels();

    @Order(6)
    @Key("languages")
    @Comment({
            "Every language the network speaks. A list, so a third language is an edit here and",
            "not a release: add the role and the two channels in Discord, add an entry, add",
            "<tag>.properties to every module's messages/ directory, restart.",
            "",
            "'en' must be present - it is the fallback everything degrades to, and a missing",
            "translation shows up as the message key rather than as nothing at all. Tags are",
            "unique, lower case, and are the bundle file names. A language is identified by its",
            "tag, so changing 'tag' on an existing entry retires that language.",
            "",
            "The ids are EMPTY here for the same reason every other id is. If you have emptied",
            "the list, this is the shape:",
            "",
            "  languages:",
            "  - tag: en",
            "    role: '000000000000000000'",
            "    contribution-channel: '000000000000000000'",
            "    link-channel: '000000000000000000'",
            "    hunger-games-channel: '000000000000000000'"
    })
    default List<LanguageSpec> languages() {
        return DefaultLanguages.LIST;
    }

    @Order(7)
    @Key("payment")
    @Comment("The bunq poll loop and the life cycle of a payment request.")
    PaymentSpec payment();

    // There is deliberately no link-code-ttl-minutes here. It used to sit between `payment` and
    // `expiry-reminder-lead-days` and nothing in this process ever read it - the sweep
    // (ReconcileDao#deleteExpiredLinkCodes) compares `expires` to now(), and the proxy is the
    // process that issues codes and therefore the only one that can act on a TTL at all.
    // `network-control`'s gate.yml#link-code-ttl-minutes is the only one, decided 2026-08-31.

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

    @Order(10)
    @Key("link-code-attempts-per-hour")
    @Comment({
            "How many WRONG link codes one Discord account may submit per hour before the modal",
            "stops answering it. Only a code that matched nothing counts; a correct code, and a",
            "code that failed because the account is already linked, do not.",
            "",
            "THIS IS HALF OF A SECURITY PROPERTY, NOT A COMFORT SETTING. A link code is four",
            "characters from a 31-symbol alphabet - 923 521 possibilities - and it is a bearer",
            "credential for taking over somebody's account link. Five guesses an hour turns the",
            "space into decades; five hundred turns it into weeks. The code was shortened to four",
            "characters ON CONDITION that this cap exists (see LinkCodes in :common), so raising",
            "it far is not tuning, it is undoing the other half of a decision.",
            "",
            "Raising it a little is fine and is why the key exists: a code lives ten minutes, so a",
            "player who mistypes it repeatedly can reach five in one sitting, and the punishment",
            "for that is an hour of waiting.",
            "",
            "The counter lives in the bot's memory and is lost on restart. Deliberate: it costs",
            "nothing against a space this size, and nobody who is guessing can restart the bot."
    })
    default int linkCodeAttemptsPerHour() {
        return 5;
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

    /**
     * One language: its tag, the onboarding role that chooses it, and the two channels that carry
     * the managed messages in it.
     * <p>
     * A list of these rather than the fixed {@code roles.german} / {@code roles.english} pair and
     * four fixed channel keys, because those made a third language a code change
     * ({@code docs/i18n.md}). jcore can carry a non-empty default for a list of nested specs - see
     * {@link DefaultLanguages} - so a fresh {@code access.yml} ships with {@code en} and {@code de}
     * rather than with a list nobody can read a message out of.
     * </p>
     */
    @ConfigSpec
    interface LanguageSpec {

        @Order(1)
        @Key("tag")
        @Comment({
                "The language tag, lower case. It is the bundle file name in every module's",
                "messages/ directory and the value stored in discord_user.locale.",
                "'en' is mandatory: it is what a missing translation falls back to."
        })
        default String tag() {
            return "";
        }

        @Order(2)
        @Key("role")
        @Comment({
                "The role Discord's own onboarding assigns for this language. Read-only for the",
                "bot - it only mirrors it into discord_user.locale, and no role at all means",
                "English."
        })
        default String role() {
            return "";
        }

        @Order(3)
        @Key("contribution-channel")
        @Comment("Carries the buy-access message in this language, and its donation thank-yous.")
        default String contributionChannel() {
            return "";
        }

        @Order(4)
        @Key("link-channel")
        @Comment("Carries the account-link message in this language.")
        default String linkChannel() {
            return "";
        }

        @Order(5)
        @Key("hunger-games-channel")
        @Comment({
                "Carries the hunger games Register message in this language - a separate channel",
                "from contribution-channel on purpose: registering for the start event and buying",
                "paid access are different things, and access is not required to play",
                "(docs/hunger-games.md)."
        })
        default String hungerGamesChannel() {
            return "";
        }

        @Order(6)
        @Key("status-channel")
        @Comment({
                "The channel this language's status line is written into, as a channel NAME - the",
                "bot renames it, it never posts in it. A voice channel is the usual shape for one",
                "of these, but any channel type works.",
                "",
                "OPTIONAL, unlike every other id in this file. Empty means this language has no",
                "status channel and the bot renames nothing - which is the default, because a",
                "channel that does not exist yet must not stop the bot from starting.",
                "",
                "What it says follows the phase: a countdown to season_phase.launch before the",
                "opening, the registered teams during PRE_EVENT, the surviving teams during the",
                "event, the registered players during SMP, and a maintenance line otherwise. The",
                "wording of each is a message key, so it is translated rather than configured here.",
                "",
                "Discord allows 2 renames per 10 minutes PER CHANNEL and blocks hard on abuse, so",
                "the bot renames at most once every six minutes and only when the text actually",
                "changed. Two languages are two channels and two independent budgets."
        })
        default String statusChannel() {
            return "";
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

        // There are deliberately no language roles here. `german` and `english` used to sit between
        // `donor` and `admin`, and they made a third language a code change - the whole reason
        // `languages` above is a list (docs/i18n.md). Each language carries its own role there.

        @Order(3)
        @Key("admin")
        @Comment({
                "Who is an admin. Read-only for the bot, exactly like the language roles on the",
                "'languages' entries below: it is mirrored into discord_user.admin and every other",
                "process reads the flag from there - the proxy on the login path, the plugins at",
                "join. An admin is appointed in Discord and is an admin everywhere; there is no",
                "second list.",
                "",
                "The mirror is live, not a grant: losing the role clears the flag on the next",
                "role event or reconcile. This is what /phase set and the MAINTENANCE phase are",
                "authorised by, so it is not a cosmetic id."
        })
        default String admin() {
            return "";
        }

        @Order(4)
        @Key("admin-ping")
        @Comment({
                "Mentioned in the admin channel for entries that need a human.",
                "Routine audit entries are written without a mention.",
                "",
                "Not the same thing as 'admin' above: this one only decides who gets pinged, and",
                "grants nobody any power. They may be the same role."
        })
        default String adminPing() {
            return "";
        }
    }

    /**
     * Channel ids the bot writes to that are not per-language.
     * <p>
     * The four per-language channels - {@code contribution-en}, {@code contribution-de},
     * {@code link-en} and {@code link-de} - used to sit here. They are gone: each entry of
     * {@link #languages()} carries its own {@code contribution-channel} and {@code link-channel},
     * which is what makes a third language an edit to this file rather than a release
     * ({@code docs/i18n.md}). The admin channel stays here because there is exactly one of it,
     * whatever languages the guild speaks.
     * </p>
     */
    @ConfigSpec
    interface ChannelsSpec {

        @Order(1)
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
