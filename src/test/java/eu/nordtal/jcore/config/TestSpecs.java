package eu.nordtal.jcore.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Reload;
import eu.nordtal.jcore.config.spec.annotation.Save;

import java.util.List;

/** The spec interfaces the config tests load. */
public final class TestSpecs {

    private TestSpecs() {
    }

    /** Modelled on the payments-bot's PaymentProcessingConfig, plus a nested section. */
    @ConfigSpec(header = {"Test configuration", "Second header line"})
    public interface Payments {

        @Order(1)
        @Key("check-interval-seconds")
        @Comment("How often the account is polled, in seconds.")
        default long checkIntervalSeconds() {
            return 10;
        }

        @Order(2)
        @Key("confirmation-channel-id")
        @Comment("Where confirmations are posted.")
        default String confirmationChannelId() {
            return "1397264662545957056";
        }

        @Order(3)
        @Key("balance")
        @Comment("Balance channel settings")
        Balance balance();

        @Reload
        void reload();

        @Save
        void save();
    }

    @ConfigSpec
    public interface Balance {

        @Order(1)
        @Key("channel-id")
        @Comment("The voice channel that shows the balance.")
        default String channelId() {
            return "1417574134958788720";
        }

        @Order(2)
        @Key("format")
        @Comment("How the channel name is rendered.")
        default String format() {
            return "%s EUR";
        }
    }

    /**
     * The shape jcore is weakest on: a list of nested objects. Modelled on nordtal-smp's
     * WorldsConfig, which is the most demanding real config in the workspace.
     */
    @ConfigSpec(header = "Worlds")
    public interface Worlds {

        @Order(1)
        @Key("worlds")
        @Comment("The configured worlds.")
        default List<World> worlds() {
            return List.of();
        }

        @Order(2)
        @Key("reset-day")
        @Comment("Day of the week the farm world resets.")
        default String resetDay() {
            return "monday";
        }
    }

    @ConfigSpec
    public interface World {

        @Order(1)
        @Key("name")
        @Comment("The world's name.")
        default String name() {
            return "world";
        }

        @Order(2)
        @Key("display-colour")
        @Comment("Hex colour used in the UI.")
        default String displayColour() {
            return "#ffffff";
        }

        @Order(3)
        @Key("preserved")
        @Comment("Whether the world survives a reset.")
        default boolean preserved() {
            return false;
        }
    }

    /** Used to prove the environment-variable collision check fires. */
    @ConfigSpec
    public interface Colliding {

        @Order(1)
        @Key("a-b")
        default String ab() {
            return "";
        }

        @Order(2)
        @Key("a")
        Nested a();
    }

    @ConfigSpec
    public interface Nested {

        @Order(1)
        @Key("b")
        default String b() {
            return "";
        }
    }
}
