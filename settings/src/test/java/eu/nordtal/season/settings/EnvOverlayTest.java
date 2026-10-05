package eu.nordtal.season.settings;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.spec.Specs;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvOverlayTest {

    @Test
    void aSetVariableWinsAndOnlyItsPathIsReported() {
        final ExampleSpec values = Specs.createDefault(ExampleSpec.class);
        final Map<String, String> variables = Map.of("NORDTAL_VIEW_DISTANCE", "12", "NORDTAL_NAME", " ");

        final List<String> overridden = EnvOverlay.forSpec(ExampleSpec.class, "NORDTAL", variables::get, SpecJson.GSON)
                .applyTo(values);

        assertAll(
                () -> assertEquals(List.of("view-distance"), overridden),
                () -> assertEquals(12, values.viewDistance()),
                () -> assertEquals("nordtal", values.name(), "a blank variable counts as unset"));
    }

    @Test
    void anUnparseableValueIsRefused() {
        final ExampleSpec values = Specs.createDefault(ExampleSpec.class);
        final Map<String, String> variables = Map.of("NORDTAL_VIEW_DISTANCE", "far");

        final RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> EnvOverlay.forSpec(ExampleSpec.class, "NORDTAL", variables::get, SpecJson.GSON)
                        .applyTo(values));
        assertTrue(error.getMessage().contains("NORDTAL_VIEW_DISTANCE"), error.getMessage());
    }

    @Test
    void aPathBecomesOneUpperCaseVariable() {
        assertEquals("NORDTAL_BALANCE_CHANNEL_ID", EnvOverlay.variableName("NORDTAL", "balance.channel-id"));
    }

    @Test
    void collidingVariableNamesAreRefused() {
        assertThrows(
                IllegalStateException.class,
                () -> EnvOverlay.forSpec(Colliding.class, "NORDTAL", name -> null, SpecJson.GSON));
    }

    /** Two keys whose variables are the same name. */
    @ConfigSpec
    public interface Colliding {

        @Key("a-b")
        default int dashed() {
            return 0;
        }

        @Key("a_b")
        default int underscored() {
            return 0;
        }
    }
}
