package eu.nordtal.s2.discordbot.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Reload;
import eu.nordtal.s2.settings.Refers;
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
    @Comment({
        "The channel holding the message where a member chooses a language and a region,",
        "and the one channel the lock role sees. OPTIONAL: empty means no message, and the",
        "lock locks nobody."
    })
    @Explain("Holds the message to choose a language and a region, and is all the lock role sees.")
    @Refers(value = Refers.To.DISCORD_CHANNEL, optional = true)
    default String channel() {
        return "";
    }

    @Order(2)
    @Name("Lock")
    @Key("lock")
    @Comment({
        "While on, a member without a language role or a region role holds the lock role,",
        "which sees the onboarding channel and nothing else. Off, nobody holds it."
    })
    @Explain("While on, a member who has not chosen a language and a region sees only the onboarding channel.")
    default boolean lock() {
        return false;
    }

    @Order(3)
    @Name("Lock role")
    @Key("lock-role")
    @Comment("The name of the lock role. The bot takes or creates it and follows it by id.")
    @Explain("The bot takes the role of exactly this name, or creates it, and then follows it even when it is renamed.")
    default String lockRole() {
        return "Onboarding";
    }

    @Order(4)
    @Name("Regions")
    @Key("regions")
    @Comment({
        "The regions a member chooses from, in the order offered. Each is a role of its",
        "name and a time zone, which becomes the player's. Zones are IANA names, such as",
        "Europe/Berlin, and unique; at most 25 regions."
    })
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
        @Comment("The role's name, and what the choice reads.")
        @Explain("The role's name, and what the choice reads.")
        default String name() {
            return "";
        }

        @Order(2)
        @Name("Time zone")
        @Key("zone")
        @Comment("An IANA time zone, such as Europe/Berlin.")
        @Explain("An IANA time zone, such as Europe/Berlin.")
        default String zone() {
            return "";
        }
    }
}
