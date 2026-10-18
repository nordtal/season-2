package eu.nordtal.season.discordbot.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import eu.nordtal.season.spec.annotation.Reload;
import java.util.List;

/** The {@code onboarding} group: the channels where a member chooses a language and a region, the regions, the lock. */
@ConfigSpec
public interface OnboardingSpec {

    /** The channel where a locked member chooses a language and a region. The lock role sees only this channel. */
    @Order(1)
    @Name("Onboarding channel")
    @Key("channel")
    @NoExplanationNeeded
    @Refers(value = Refers.To.DISCORD_CHANNEL, optional = true)
    default String channel() {
        return "";
    }

    @Order(2)
    @Name("Change channel")
    @Key("change-channel")
    @NoExplanationNeeded
    @Refers(value = Refers.To.DISCORD_CHANNEL, optional = true)
    default String changeChannel() {
        return "";
    }

    @Order(3)
    @Name("Lock")
    @Key("lock")
    @Explain("On, a member without a language and a region sees only the onboarding channel.")
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

    /** Each region is a role of its name. A member who holds the role reads times in its zone. */
    @Order(5)
    @Name("Regions")
    @Key("regions")
    @NoExplanationNeeded
    default List<RegionSpec> regions() {
        return DefaultRegions.LIST;
    }

    @Reload
    void reload();

    @ConfigSpec
    interface RegionSpec {

        @Order(1)
        @Name("Name")
        @Key("name")
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
    }
}
