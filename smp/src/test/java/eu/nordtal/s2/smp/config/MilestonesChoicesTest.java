package eu.nordtal.s2.smp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.schema.SchemaNode;
import eu.nordtal.jcore.config.schema.SchemaWriter;
import eu.nordtal.s2.smp.milestone.ObjectiveType;
import eu.nordtal.s2.smp.milestone.Unlock;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two fields of the track that only take a fixed word are offered as a closed list in the config editor.
 *
 * That list is the enum the plugin reads them into - a constant added to one and not the other would be a value
 * the editor refuses or one the plugin does.
 */
class MilestonesChoicesTest {

    private static final SchemaNode MILESTONE =
            SchemaWriter.build(MilestonesSpec.class).children().get("milestones");

    @Test
    void unlocksIsAClosedListOfEveryUnlock() {
        assertClosedList(names(Unlock.values()), MILESTONE.children().get("unlocks"));
    }

    @Test
    void anObjectivesTypeIsAClosedListOfEveryObjectiveType() {
        assertClosedList(
                names(ObjectiveType.values()),
                MILESTONE.children().get("objectives").children().get("type"));
    }

    private static void assertClosedList(final List<String> expected, final SchemaNode node) {
        assertNotNull(node.choices(), "no choices - the editor falls back to free text");
        assertEquals(expected, node.choices().values());
        assertTrue(node.choices().strict(), "a word the plugin cannot read has to be refused");
    }

    private static List<String> names(final Enum<?>[] constants) {
        return Arrays.stream(constants).map(Enum::name).toList();
    }
}
