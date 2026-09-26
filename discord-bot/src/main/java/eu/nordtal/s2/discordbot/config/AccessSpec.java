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
 * {@code config/access.yml} - everything about the product, the guild and the poll loop.
 *
 * <p>Nothing here is an enum: prices, day counts, role ids and channel ids all live in this file,
 * and the code only knows that a tier costs money and buys days.</p>
 *
 * <p>Every id defaults to empty and the bot refuses to start while one is - a real id as a default
 * would mean an unreadable config falls back to writing into somebody's production channel.</p>
 *
 * @see Configs#access()
 */
@ConfigSpec(
        header = {
            "-------------------------------------------------------------------",
            "  access-bot - the product, the guild and the poll loop",
            "-------------------------------------------------------------------",
            "Every setting here can be overridden with an environment variable",
            "named NORDTAL_ACCESS_<PATH>, with '.' and '-' both becoming '_':",
            "",
            "  donation-cents  ->  NORDTAL_ACCESS_DONATION_CENTS",
            "",
            "The environment wins over this file and is never written back into",
            "it. A setting this file does not declare is deleted on the next",
            "start, with a warning and a copy of the old file in access.yml.bak",
            "- unless it looks like a MISSPELLING of a real one, which stops the",
            "bot instead, because only you know what you meant by it.",
            "",
            "The role and channel ids are EMPTY by default and the bot will not",
            "start until they are filled in. That is deliberate: a default id",
            "would be a real channel in somebody's guild."
        })
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
        "It is also how a payment larger than the order is read: money left over above the",
        "ordered total is a donation once it reaches this amount, and is otherwise ignored."
    })
    @Explain(
            "The extra amount that grants the donor role - also how a payment above the order total is recognised as a donation.")
    default int donationCents() {
        return 500;
    }

    @Order(4)
    @Name("Roles")
    @Key("roles")
    @Comment({
        "Role ids that are not per-language. Snowflakes, as strings - a snowflake does not fit",
        "in a YAML integer safely. Each language's own role is on its entry under 'languages'."
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
    // steward/74: this is the one place the "'en' is mandatory" rule is declared as data rather
    // than prose or an `if`. Configs#validateLanguages reads this same annotation rather than
    // holding its own copy of the tag, and steward-worker's schema-reading side (ConfigFiles,
    // steward-ui) refuses to let an operator remove the entry it names before the file is ever
    // touched. `value` is Languages.FALLBACK_TAG rather than a second "en" literal - an annotation
    // value has to be a compile-time constant, and that field already is one.
    @Protected(field = "tag", value = Languages.FALLBACK_TAG)
    @Explain(
            "Every language the network speaks. The 'en' entry cannot be removed - it is the fallback a missing translation degrades to.")
    default List<LanguageSpec> languages() {
        return DefaultLanguages.LIST;
    }

    @Order(7)
    @Name("Payment")
    @Key("payment")
    @Comment("The life cycle of a payment request, and how often the bot re-reads the seam.")
    @Explain("The life cycle of a payment request, and how often the bot re-reads the payment seam.")
    PaymentSpec payment();

    // There is deliberately no link-code-ttl-minutes here: the proxy issues the codes and is the
    // only process that can act on a TTL. proxy's gate.yml owns the only one.

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
        "How often the access role is reconciled against the database.",
        "This walks the members who hold the role plus the users who hold a grant - it is",
        "not a full member scan, so it can be frequent without being expensive."
    })
    @NoExplanationNeeded
    default int roleReconcileIntervalMinutes() {
        return 10;
    }

    @Order(10)
    @Name("Link code attempts per hour")
    @Key("link-code-attempts-per-hour")
    @Comment({
        "How many WRONG link codes one Discord account may submit per hour before the modal",
        "stops answering it. Only a code that matched nothing counts; a correct code, and a",
        "code that failed because the account is already linked, do not.",
        "",
        "THIS IS HALF OF A SECURITY PROPERTY, NOT A COMFORT SETTING. A link code is four",
        "characters from a 31-symbol alphabet - 923 521 possibilities - and it is a bearer",
        "credential for taking over somebody's account link. Five guesses an hour turns the",
        "space into decades; five hundred turns it into weeks. The code is only four characters",
        "BECAUSE this cap exists, so raising it far undoes the other half of that decision.",
        "",
        "Raising it a little is why the key exists: a player who mistypes a code repeatedly can",
        "reach five in one sitting, and the punishment is an hour of waiting.",
        "",
        "The counter lives in memory and is lost on restart - it costs nothing against a space",
        "this size, and nobody who is guessing can restart the bot."
    })
    @Explain(
            "A security limit, not a comfort setting: raising it weakens the four-character link code's brute-force resistance from decades toward weeks.")
    default int linkCodeAttemptsPerHour() {
        return 5;
    }

    @Reload
    void reload();

    /**
     * One purchasable period: a number of days for a price. A list rather than fixed keys, so a
     * fourth tier is an edit and not a release; {@link DefaultTiers} keeps a fresh
     * {@code access.yml} shipping a usable price list rather than an empty one.
     */
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

    /**
     * One language: its tag, the onboarding role that chooses it, and the channels that carry the
     * managed messages in it. A list rather than fixed per-language keys, so a third language is an
     * edit and not a code change; {@link DefaultLanguages} ships {@code en} and {@code de}.
     */
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
                "Lower case - the bundle file name in every module's messages/ directory and the value stored for a player's locale.")
        default String tag() {
            return "";
        }

        @Order(2)
        @Name("Discord role")
        @Key("role")
        @Comment({
            "The role Discord's own onboarding assigns for this language. Read-only for the",
            "bot - it only mirrors it into discord_user.locale, and no role at all means",
            "English."
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
            "Carries the hunger games Register message in this language - a separate channel",
            "from contribution-channel on purpose: registering for the start event and buying",
            "paid access are different things, and access is not required to play."
        })
        @Explain(
                "Carries the hunger games registration message - separate from contribution-channel, since access is not required to play.")
        default String hungerGamesChannel() {
            return "";
        }

        @Order(6)
        @Name("Status channel")
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
        @Explain(
                "Optional - a channel the bot renames (never posts in) to show this language's current status. Empty means none.")
        default String statusChannel() {
            return "";
        }

        @Order(7)
        @Name("Announcement channel")
        @Key("announcement-channel")
        @Comment({
            "The channel this language's announcements are POSTED into, as a channel id: a",
            "milestone the community finished, a season phase that changed. Written by the",
            "bot, read by everybody.",
            "",
            "OPTIONAL, like status-channel: empty means this language gets no announcements",
            "and every line the servers send for it settles as \"no channel\" in",
            "command_request, which is the default so that a channel nobody has created yet",
            "does not stop the bot from starting.",
            "",
            "The wording comes from the SERVER that had the moment (the SMP's own bundle, in",
            "this language), not from the bot - the bot has no copy of the milestones and",
            "must not need one."
        })
        @Explain(
                "Optional - a channel the bot posts milestones and season announcements into for this language. Empty means none.")
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
            "Strictly bot-owned. It is added and removed to match the database, so granting",
            "it by hand holds only until the next reconcile. /grant-access is the way."
        })
        @Explain("Bot-managed: granting it by hand only holds until the next reconcile. Use /grant-access instead.")
        default String access() {
            return "";
        }

        @Order(2)
        @Name("Donor role")
        @Key("donor")
        @Comment({
            "Granted on a donation and never taken away, by the bot or by the reconcile.",
            "That is what makes handing it out by hand in Discord's role UI safe."
        })
        @Explain("Granted on a donation and never revoked - safe to hand out manually in Discord.")
        default String donor() {
            return "";
        }

        // There are deliberately no language roles here - each language carries its own role on
        // its `languages` entry, which is what keeps a third language out of the code.

        @Order(3)
        @Name("Admin role")
        @Key("admin")
        @Comment({
            "The role every admin carries in Discord. Who is an admin is decided in",
            "Steward, on the Users page, and stored in discord_user.admin; the bot keeps this",
            "role in step with it - adds it to every admin and takes it from everybody else,",
            "a role handed out by hand included. It never works the other way round.",
            "",
            "The bot's own role has to sit above this one in the guild's role list, or",
            "Discord refuses both the add and the removal."
        })
        @Explain(
                "Follows the admins decided in Steward - the bot adds and removes it, and never reads it as a permission.")
        default String admin() {
            return "";
        }

        @Order(4)
        @Name("Admin ping role")
        @Key("admin-ping")
        @Comment({
            "Mentioned in the admin channel for entries that need a human.",
            "Routine audit entries are written without a mention.",
            "",
            "Not the same thing as 'admin' above: this one only decides who gets pinged, and",
            "grants nobody any power. They may be the same role."
        })
        @Explain(
                "Only decides who is pinged in the admin channel - grants no power, and may be the same role as admin.")
        default String adminPing() {
            return "";
        }
    }

    /**
     * Channel ids the bot writes to that are not per-language. The per-language ones live on the
     * {@link #languages()} entries; the admin channel is here because there is exactly one of it,
     * whatever languages the guild speaks.
     */
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

    /**
     * The life cycle of a payment request, and how often the bot looks at the seam.
     *
     * <h2>What is no longer here (steward/109)</h2>
     * {@code watermark} and {@code recent-payment-count} moved to {@code bunq:} in steward-worker's
     * {@code steward.yml}, because both are questions you can only ask a process that talks to
     * bunq: which payments are old enough to ignore, and how many of the account's recent payments
     * to scan. Neither had any meaning in a process with no bank connection.
     *
     * <h2>Why the TTL did not move with them</h2>
     * {@code request-ttl-hours} is used <b>twice in this process and nowhere else</b>: it is stamped
     * into {@code payment_request.expires} at the moment the row is written, and it is the number in
     * "this link is valid for N hours" on the message the buyer is looking at. Those two have to be
     * the same number, and the row is written here. The worker reads {@code expires}, never the
     * setting - which is the right split: the bot decides how long it is offering, the worker acts
     * on what was decided. Moving it would have made the sentence and the column two settings that
     * agree by convention.
     */
    @ConfigSpec
    interface PaymentSpec {

        @Order(1)
        @Name("Poll interval (seconds)")
        @Key("poll-interval-seconds")
        @Comment({
            "How often the bot re-reads the payment seam: money steward-worker has matched and",
            "not yet booked, notices waiting for the admin channel, and payment links somebody",
            "is waiting for.",
            "",
            "This is a query against this database, not a call to a bank - steward-worker holds",
            "the bunq key and has a poll interval of its own. nordtal_payment makes each of",
            "these feel instant; this is only the guarantee underneath it."
        })
        @Explain(
                "How often the bot re-reads the payment seam in the database. Notifications make it feel instant; this is the fallback.")
        default int pollIntervalSeconds() {
            return 30;
        }

        @Order(2)
        @Name("Request lifetime (hours)")
        @Key("request-ttl-hours")
        @Comment({
            "How long an unpaid request stays open. Past this the bunq tab is cancelled and",
            "the request goes to EXPIRED; a payment arriving afterwards is never booked",
            "automatically - it goes to the admin channel.",
            "",
            "It stays with the bot and not with steward-worker because the bot is what writes",
            "the row: this number becomes payment_request.expires and it is the same number the",
            "buyer is told the link is good for. The worker expires rows by reading that column."
        })
        @Explain("Past this, the bunq tab is cancelled and a late payment needs manual handling in the admin channel.")
        default int requestTtlHours() {
            return 24;
        }
    }
}
