package eu.nordtal.season.smp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.displaytags.config.spec.NameTagConfigurationSpec;
import eu.nordtal.season.spec.schema.SchemaWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Name tags are always drawn and their text is smp's, so the group holds only how a tag looks, at one level. */
class NameTagsSchemaTest {

    @Test
    void theGroupHoldsTheLookDirectlyWithoutSwitchOrLines() {
        assertEquals(
                List.of(
                        "show-to-self",
                        "update-interval",
                        "visibility-distance",
                        "text-shadow",
                        "see-through",
                        "sneak-text-opacity",
                        "text-alignment",
                        "background",
                        "billboard",
                        "offset",
                        "scale"),
                List.copyOf(SchemaWriter.build(NameTagConfigurationSpec.class)
                        .children()
                        .keySet()));
    }
}
