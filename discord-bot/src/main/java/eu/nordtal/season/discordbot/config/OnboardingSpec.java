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

/**
 * The {@code onboarding} group: where a member chooses a language and a region, the regions, and the lock.
 *
 * Taken while the bot runs, so a change applies at once.
 */
@ConfigSpec
public interface OnboardingSpec {

    /** Where a locked member chooses a language and a region; it is all the lock role sees. */
    @Order(1)
    @Name("Onboarding channel")
    @Key("channel")
    @NoExplanationNeeded
    @Refers(value = Refers.To.DISCORD_CHANNEL, optional = true)
    default String channel() {
        return "";
    }

    /** Holds the message to change a language or a region later. */
    @Order(2)
    @Name("Change channel")
    @Key("change-channel")
    @Explain("Not the onboarding channel.")
    @Refers(value = Refers.To.DISCORD_CHANNEL, optional = true)
    default String changeChannel() {
        return "";
    }

    @Order(3)
    @Name("Lock")
    @Key("lock")
    @Explain("While on, a member who has not chosen a language and a region sees only the onboarding channel.")
    default boolean lock() {
        return false;
    }

    /** Found by its exact name, created when missing, and followed through a rename. */
    @Order(4)
    @Name("Lock role")
    @Key("lock-role")
    @NoExplanationNeeded
    default String lockRole() {
        return "Onboarding";
    }

    /** Each region is a role of its name; a member who holds it reads times in its zone. */
    @Order(5)
    @Name("Regions")
    @Key("regions")
    @NoExplanationNeeded
    default List<RegionSpec> regions() {
        return DefaultRegions.LIST;
    }

    @Reload
    void reload();

    /** One region: the name of its role and the time zone it stands for. */
    @ConfigSpec
    interface RegionSpec {

        @Order(1)
        @Name("Name")
        @Key("name")
        @NoExplanationNeeded
        default String name() {
            return "";
        }

        @Order(2)
        @Name("Time zone")
        @Key("zone")
        @Explain("An IANA time zone, such as Europe/Berlin.")
        default String zone() {
            return "";
        }
    }
}
