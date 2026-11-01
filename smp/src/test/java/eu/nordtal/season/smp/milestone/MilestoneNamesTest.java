package eu.nordtal.season.smp.milestone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.database.document.DocumentStore;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.messages.document.Part;
import eu.nordtal.season.smp.config.MilestonesSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MilestoneNamesTest {

    private static final Messages MESSAGES = Messages.load("messages/database", Locale.ENGLISH, Locale.GERMAN);

    private static final MessageRenderer RENDERER = MessageRenderer.of(MESSAGES).reading(documents());

    private static AppliedDocuments documents() {
        return new AppliedDocuments(MilestoneNamesTest.class.getClassLoader(), Set.of("smp"));
    }

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
    void anObjectiveIsNamedWithinItsMilestone() {
        assertEquals("Holzstämme", MilestoneNames.objective(RENDERER, Locale.GERMAN, "foothold", "logs"));
        assertEquals("Iron ingots", MilestoneNames.objective(RENDERER, Locale.ENGLISH, "settlement", "iron"));
        assertEquals("logs", MilestoneNames.objective(RENDERER, Locale.ENGLISH, "settlement", "logs"));
    }

    @Test
    void everyMilestoneAndObjectiveOfTheDefaultTrackHasAName() {
        // A missing line shows the id, which no player should read on the default track.
        for (final MilestonesSpec.MilestoneEntry milestone : new MilestonesSpec() {}.milestones()) {
            for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
                assertFalse(
                        MilestoneNames.of(RENDERER, locale, milestone.key()).equals(milestone.key()), milestone.key());
                for (final MilestonesSpec.ObjectiveEntry objective : milestone.objectives()) {
                    assertFalse(
                            MilestoneNames.objective(RENDERER, locale, milestone.key(), objective.key())
                                    .equals(objective.key()),
                            milestone.key() + "/" + objective.key());
                }
            }
        }
    }

    @Test
    void aMilestoneAndAnObjectiveAnAdminAddsAreNamedByTheirOwnLines() {
        final AppliedDocuments documents = documents();
        documents.hold(List.of(added("milestone-name", """
                        {"type": "TEXT", "lines": [{"when": "milestone=Harbour_2", "text": {"en": "Harbour", "de": "Hafen"}}]}
                        """), added("objective-name", """
                        {"type": "TEXT", "when": "milestone=Harbour_2",
                          "lines": [{"when": "objective=nets", "text": {"en": "Nets", "de": "Netze"}}]}
                        """)));
        final MessageRenderer renderer = MessageRenderer.of(MESSAGES).reading(documents);

        assertEquals("Hafen", MilestoneNames.of(renderer, Locale.GERMAN, "Harbour_2"));
        assertEquals("Netze", MilestoneNames.objective(renderer, Locale.GERMAN, "Harbour_2", "nets"));
        assertEquals("Aufbruch", MilestoneNames.of(renderer, Locale.GERMAN, "departure"));
    }

    /** Returns the packaged document of {@code name} with {@code part} added, as an admin saves it. */
    private static DocumentStore.StoredDocument added(final String name, final String part) {
        final Documents.Packaged packaged = Documents.packaged(MilestoneNamesTest.class.getClassLoader(), "smp")
                .get(name);
        final List<Part> parts = new ArrayList<>(packaged.document().parts());
        parts.addAll(Document.decode("{\"parts\": [" + part + "]}").parts());
        return new DocumentStore.StoredDocument("smp", name, new Document(parts), packaged.hash());
    }
}
