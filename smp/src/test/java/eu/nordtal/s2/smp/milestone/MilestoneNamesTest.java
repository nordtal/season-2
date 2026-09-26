package eu.nordtal.s2.smp.milestone;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.spec.MessageSchema;
import eu.nordtal.s2.smp.SmpMessages;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MilestoneNamesTest {

    private static final Messages BUNDLE =
            Messages.load(MilestoneNamesTest.class.getClassLoader(), "messages/smp", Locale.ENGLISH, Locale.GERMAN);

    @Test
    @DisplayName("a shipped milestone shows its name in the reader's language")
    void aShippedMilestoneIsNamed() {
        assertEquals("Aufbruch", MilestoneNames.of(BUNDLE, Locale.GERMAN, "departure"));
        assertEquals("Departure", MilestoneNames.of(BUNDLE, Locale.ENGLISH, "departure"));
    }

    @Test
    @DisplayName("a milestone only the config knows shows under its key")
    void anUnknownMilestoneShowsItsKey() {
        assertEquals("harbour", MilestoneNames.of(BUNDLE, Locale.GERMAN, "harbour"));
    }

    @Test
    @DisplayName("every milestone name the bundle ships is reachable from its config key")
    void everyShippedNameIsMapped() {
        // A name added to the spec without a case in the switch would never be shown: the milestone
        // would appear under its config key and nothing would say why.
        final String prefix = "smp.milestone.";
        MessageSchema.entries(SmpMessages.class).stream()
                .map(MessageSchema.Entry::key)
                .filter(key -> key.startsWith(prefix) && key.indexOf('.', prefix.length()) < 0)
                .filter(key -> !key.equals(prefix + "completed"))
                .forEach(key -> assertTrue(
                        MESSAGES.smp()
                                .milestoneName(key.substring(prefix.length()))
                                .filter(name -> name.key().equals(key))
                                .isPresent(),
                        key + " has no case"));
    }
}
