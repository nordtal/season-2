package eu.nordtal.season.discordbot.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.settings.rule.Entries;
import eu.nordtal.season.settings.rule.Required;
import eu.nordtal.season.settings.rule.Unique;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import eu.nordtal.season.spec.annotation.Reload;
import java.util.List;

/** The {@code onboarding} group: the choose and change channels, countries and lock. */
@ConfigSpec
public interface OnboardingSpec {

    /** The channel where a member without a country chooses one. Only the lock role sees it. */
    @Order(1)
    @Name("Choose channel")
    @Key("channel")
    @Explain("Only members without a country see it. Its message is in English.")
    @Refers(value = Refers.To.DISCORD_CHANNEL, optional = true)
    default String channel() {
        return "";
    }

    /** The channel where a member opens their country and language settings. */
    @Order(2)
    @Name("Change channel")
    @Key("change-language-channel")
    @Explain("Every member with a country sees it. Its message is in every language.")
    @Refers(value = Refers.To.DISCORD_CHANNEL, optional = true)
    default String changeChannel() {
        return "";
    }

    @Order(3)
    @Name("Lock")
    @Key("lock")
    @Explain("On, a member without a country sees only the choose channel.")
    default boolean lock() {
        return false;
    }

    /** The bot finds the role by its exact name, creates it when missing and follows a rename. */
    @Order(4)
    @Name("Lock role")
    @Key("lock-role")
    @NoExplanationNeeded
    default String lockRole() {
        return "Onboarding";
    }

    /** Each country is a role whose language and time zone apply to a member. */
    @Order(5)
    @Name("Countries")
    @Key("countries")
    @Required
    @Entries(most = BotSettings.MAX_CHOICES)
    @Unique("key")
    @NoExplanationNeeded
    default List<CountrySpec> countries() {
        return DefaultCountries.LIST;
    }

    @Reload
    void reload();

    @ConfigSpec
    interface CountrySpec {

        @Order(1)
        @Name("Key")
        @Key("key")
        @Required
        @NoExplanationNeeded
        default String key() {
            return "";
        }

        @Order(1)
        @Name("Name")
        @Key("name")
        @Required
        @NoExplanationNeeded
        default String name() {
            return "";
        }

        /** An IANA time zone, such as Europe/Berlin. */
        @Order(2)
        @Name("Time zone")
        @Key("zone")
        @NoExplanationNeeded
        default String zone() {
            return "";
        }

        @Order(3)
        @Name("Language")
        @Key("language")
        @Required
        @NoExplanationNeeded
        default String language() {
            return "";
        }

        @Order(4)
        @Name("Discord emoji")
        @Key("emoji")
        @Required
        @NoExplanationNeeded
        default String emoji() {
            return "";
        }

        @Order(5)
        @Name("Flag glyph")
        @Key("glyph")
        @Required
        @NoExplanationNeeded
        default String glyph() {
            return "";
        }
    }
}
