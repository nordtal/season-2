package eu.nordtal.season.discordbot.config;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.settings.rule.Entries;
import eu.nordtal.season.settings.rule.Longest;
import eu.nordtal.season.settings.rule.Positive;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import eu.nordtal.season.spec.annotation.Protected;
import eu.nordtal.season.spec.annotation.Reload;
import java.util.List;

@ConfigSpec
public interface AccessSpec {

    @Order(1)
    @Name("Guild ID")
    @Key("guild-id")
    @NoExplanationNeeded
    default String guildId() {
        return "";
    }

    @Order(2)
    @Name("Roles")
    @Key("role-names")
    @NoExplanationNeeded
    RoleNamesSpec roleNames();

    @Order(3)
    @Name("Channels")
    @Key("channels")
    @NoExplanationNeeded
    ChannelsSpec channels();

    @Order(4)
    @Name("Languages")
    @Key("languages")
    @Entries(most = BotSettings.MAX_CHOICES)
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
    @Positive
    @NoExplanationNeeded
    default int expiryReminderLeadDays() {
        return 3;
    }

    @Order(7)
    @Name("Role sync interval (minutes)")
    @Key("role-reconcile-interval-minutes")
    @Positive
    @NoExplanationNeeded
    default int roleReconcileIntervalMinutes() {
        return 10;
    }

    @Order(8)
    @Name("Link code attempts per hour")
    @Key("link-code-attempts-per-hour")
    @Positive
    @Explain("Raising it weakens the link code against guessing.")
    default int linkCodeAttemptsPerHour() {
        return 5;
    }

    @Reload
    void reload();

    @ConfigSpec
    interface LanguageSpec {

        /**
         * The bundle file name in every module's messages directory.
         * It is also the value stored as a player's locale.
         */
        @Order(1)
        @Name("Tag")
        @Key("tag")
        @Longest(BotSettings.MAX_TAG_LENGTH)
        @NoExplanationNeeded
        default String tag() {
            return "";
        }

        /** Empty means the language's own name, such as Deutsch. */
        @Order(2)
        @Name("Role name")
        @Key("role-name")
        @NoExplanationNeeded
        default String roleName() {
            return "";
        }

        @Order(7)
        @Name("Category")
        @Key("category")
        @Explain("Other languages cannot see this category or its channels.")
        @Refers(value = Refers.To.DISCORD_CHANNEL, optional = true)
        default String category() {
            return "";
        }

        @Order(8)
        @Name("Rules channel")
        @Key("rules-channel")
        @NoExplanationNeeded
        @Refers(value = Refers.To.DISCORD_CHANNEL, optional = true)
        default String rulesChannel() {
            return "";
        }

        /** The join message (access and account link) in this language goes here. */
        @Order(3)
        @Name("Join channel")
        @Key("link-channel")
        @NoExplanationNeeded
        @Refers(Refers.To.DISCORD_CHANNEL)
        default String linkChannel() {
            return "";
        }

        /**
         * The Hunger Games registration message goes here.
         * It is separate from the join channel, because access is not required to play.
         */
        @Order(4)
        @Name("Hunger Games channel")
        @Key("hunger-games-channel")
        @NoExplanationNeeded
        @Refers(Refers.To.DISCORD_CHANNEL)
        default String hungerGamesChannel() {
            return "";
        }

        /** The bot only renames it and never posts in it. Empty means none. */
        @Order(5)
        @Name("Status channel")
        @Key("status-channel")
        @NoExplanationNeeded
        @Refers(value = Refers.To.DISCORD_CHANNEL, optional = true)
        default String statusChannel() {
            return "";
        }

        /** Empty means the default: the flag of Germany for German, a globe for every other language. */
        @Order(9)
        @Name("Discord emoji")
        @Key("emoji")
        @Explain("Shown before the language's name in the language select.")
        default String emoji() {
            return "";
        }

        /** Default channel for Steward announcements and the News button in the rules embed. */
        @Order(6)
        @Name("News channel")
        @Key("announcement-channel")
        @NoExplanationNeeded
        @Refers(value = Refers.To.DISCORD_CHANNEL, optional = true)
        default String announcementChannel() {
            return "";
        }
    }

    @ConfigSpec
    interface RoleNamesSpec {

        /** The bot undoes a hand-granted role at the next reconcile. Use /grant-access. */
        @Order(1)
        @Name("Access role")
        @Key("access")
        @NoExplanationNeeded
        default String access() {
            return "Access";
        }

        /**
         * The bot grants it on a donation and never revokes it.
         * Handing it out in Discord is safe.
         */
        @Order(2)
        @Name("Donor role")
        @Key("donor")
        @NoExplanationNeeded
        default String donor() {
            return "Donor";
        }

        /**
         * The bot adds and removes it as the admins decided in Steward.
         * It never reads it as a permission.
         */
        @Order(3)
        @Name("Admin role")
        @Key("admin")
        @NoExplanationNeeded
        default String admin() {
            return "Admin";
        }
    }

    @ConfigSpec
    interface ChannelsSpec {

        /**
         * It receives everything that needs a human.
         * That is unmatchable or expired payments, failed DMs, role errors, and every link or unlink.
         */
        @Order(1)
        @Name("Admin channel")
        @Key("admin")
        @NoExplanationNeeded
        @Refers(Refers.To.DISCORD_CHANNEL)
        default String admin() {
            return "";
        }
    }

    @ConfigSpec
    interface PaymentSpec {

        /** A late payment then needs manual handling in the admin channel. */
        @Order(2)
        @Name("Request lifetime (hours)")
        @Key("request-ttl-hours")
        @Positive
        @NoExplanationNeeded
        default int requestTtlHours() {
            return 24;
        }
    }
}
