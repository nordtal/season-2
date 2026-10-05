package eu.nordtal.season.discordbot.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
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

    @Order(1)
    @Name("Onboarding channel")
    @Key("channel")
    @Explain("Holds the message to choose a language and a region, and is all the lock role sees.")
    @Refers(value = Refers.To.DISCORD_CHANNEL, optional = true)
    default String channel() {
        return "";
    }

    @Order(2)
    @Name("Lock")
    @Key("lock")
    @Explain("While on, a member who has not chosen a language and a region sees only the onboarding channel.")
    default boolean lock() {
        return false;
    }

    @Order(3)
    @Name("Lock role")
    @Key("lock-role")
    @Explain("The bot takes the role of exactly this name, or creates it, and then follows it even when it is renamed.")
    default String lockRole() {
        return "Onboarding";
    }

    @Order(4)
    @Name("Regions")
    @Key("regions")
    @Explain("Each region is a role of its name; a member who holds it reads times in its zone.")
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
        @Explain("The role's name, and what the choice reads.")
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
