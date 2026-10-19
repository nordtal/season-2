package eu.nordtal.season.smp.milestone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Messages;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MilestoneNamesTest {

    private static final MessageRenderer RENDERER = MessageRenderer.of(
                    Messages.load("messages/database", Locale.ENGLISH, Locale.GERMAN))
            .reading(new AppliedDocuments(MilestoneNamesTest.class.getClassLoader(), Set.of("smp")));

    @Test
    void aShippedMilestoneIsNamed() {
        assertEquals("Aufbruch", MilestoneNames.of(RENDERER, Locale.GERMAN, "departure"));
        assertEquals("Departure", MilestoneNames.of(RENDERER, Locale.ENGLISH, "departure"));
    }

    @Test
    void anUnknownMilestoneShowsItsKey() {
        assertEquals("harbour", MilestoneNames.of(RENDERER, Locale.GERMAN, "harbour"));
    }

    @Test
    void everyShippedMilestoneHasAName() {
        // A milestone without a line would show an empty name instead of its key.
        for (final MilestoneNames.Named named : MilestoneNames.Named.values()) {
            final String key = named.name().toLowerCase(Locale.ROOT);
            assertFalse(MilestoneNames.of(RENDERER, Locale.ENGLISH, key).isBlank(), key);
        }
    }
}
