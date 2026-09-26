package eu.nordtal.jcore.config;

import eu.nordtal.jcore.config.spec.annotation.AllowedValues;
import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Reload;
import eu.nordtal.jcore.config.spec.annotation.Save;
import eu.nordtal.jcore.config.spec.annotation.Secret;
import java.util.List;

/** The spec interfaces the config tests load. */
public final class TestSpecs {

    private TestSpecs() {}

    /** Modelled on the payments-bot's PaymentProcessingConfig, plus a nested section. */
    @ConfigSpec(header = {"Test configuration", "Second header line"})
    public interface Payments {

        @Order(1)
        @Key("check-interval-seconds")
        @Comment("How often the account is polled, in seconds.")
        @Explain("How often payments are checked, in seconds.")
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

    /** A list of nested objects - the shape jcore's serialization is weakest on. */
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

    /**
     * Covers every schema-only annotation in one place.
     *
     * {@code @Explain} beside a long {@code @Comment}, {@code @NoExplanationNeeded}, {@code @Secret}, a strict
     * and a suggestion {@code @AllowedValues}, and a plain {@code enum} property that needs none of them.
     */
    @ConfigSpec(header = "Schema example")
    public interface SchemaExample {

        @Order(1)
        @Key("mode")
        @Comment({
            "Controls how strictly an input that is not on the known list is handled.",
            "",
            "STRICT refuses it outright. LOOSE accepts it and logs a warning instead of",
            "failing the whole request over a value nobody has taught this setting about yet."
        })
        @Explain("How strictly an unknown value is rejected.")
        default Mode mode() {
            return Mode.STRICT;
        }

        @Order(2)
        @Key("accent-colour")
        @AllowedValues(
                value = {"red", "green", "blue"},
                strict = false)
        @Explain("Suggested accent colour - anything else is accepted too.")
        default String accentColour() {
            return "blue";
        }

        @Order(3)
        @Key("region")
        @AllowedValues({"eu", "us"})
        @Explain("Which region this instance serves.")
        default String region() {
            return "eu";
        }

        @Order(4)
        @Key("internal-id")
        @NoExplanationNeeded
        default String internalId() {
            return "";
        }

        @Order(5)
        @Key("api-token")
        @Secret
        @Explain("Credential for the upstream API.")
        default String apiToken() {
            return "";
        }

        enum Mode {
            STRICT,
            LOOSE
        }
    }
}
