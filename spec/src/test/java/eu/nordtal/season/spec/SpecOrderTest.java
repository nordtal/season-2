package eu.nordtal.season.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks that the fields of a spec come in a fixed order, whatever order reflection returns its methods in. */
class SpecOrderTest {

    @Test
    void fieldsWithoutAnOrderKeepTheirDeclarationOrder() {
        assertEquals(
                List.of("world", "x", "y", "z", "yaw", "pitch", "label", "colour"),
                List.copyOf(Specs.from(Unordered.class).properties().keySet()));
    }

    @Test
    void fieldsWithAnOrderComeAfterThoseWithoutInTheirOrder() {
        assertEquals(
                List.of("world", "z", "yaw", "y", "x"),
                List.copyOf(Specs.from(Mixed.class).properties().keySet()));
    }

    @ConfigSpec
    interface Unordered {

        @Key("world")
        default String world() {
            return "";
        }

        @Key("x")
        default double x() {
            return 0;
        }

        @Key("y")
        default double y() {
            return 0;
        }

        @Key("z")
        default double z() {
            return 0;
        }

        @Key("yaw")
        default float yaw() {
            return 0;
        }

        @Key("pitch")
        default float pitch() {
            return 0;
        }

        @Key("label")
        default String label() {
            return "";
        }

        @Key("colour")
        default String colour() {
            return "";
        }
    }

    @ConfigSpec
    interface Mixed {

        @Order(2)
        @Key("x")
        default double x() {
            return 0;
        }

        @Order(1)
        @Key("y")
        default double y() {
            return 0;
        }

        @Key("world")
        default String world() {
            return "";
        }

        @Key("z")
        default double z() {
            return 0;
        }

        @Key("yaw")
        default float yaw() {
            return 0;
        }
    }
}
