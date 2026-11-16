package eu.nordtal.season.database.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.npc.Dialogue.Answer;
import eu.nordtal.season.database.npc.Dialogue.Step;
import eu.nordtal.season.database.npc.Dialogue.Words;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Names what keeps a dialogue from playing. */
class DialogueTest {

    private static final Words SAY = new Words("Hi", "Moin");

    private static Step step(
            final String id, final List<Answer> answers, final @Nullable String next, final @Nullable String menu) {
        return new Step(id, 0, 0, SAY, answers, next, menu);
    }

    @Test
    void aDialogueOfAnswersAndAHandOffPlays() {
        final Dialogue dialogue = new Dialogue(
                "a",
                List.of(
                        step("a", List.of(new Answer(SAY, "b"), new Answer(SAY, null)), null, null),
                        step("b", List.of(), "c", null),
                        step("c", List.of(), null, "objectives")));

        assertEquals(List.of(), dialogue.problems(Set.of("objectives")));
    }

    @Test
    void everyWrongStepIsNamed() {
        final Dialogue dialogue = new Dialogue(
                "start",
                List.of(
                        step("a", List.of(new Answer(SAY, "b")), "b", null),
                        step("a", List.of(), null, null),
                        step("b", List.of(new Answer(SAY, null)), null, "shop"),
                        new Step("c", 0, 0, new Words("Hi", " "), List.of(), "gone", null),
                        step(
                                "d",
                                List.of(
                                        new Answer(SAY, null),
                                        new Answer(SAY, null),
                                        new Answer(SAY, null),
                                        new Answer(SAY, null)),
                                null,
                                null)));

        assertEquals(
                List.of(
                        "Two steps are called a.",
                        "The dialogue starts at start, which is no step.",
                        "Step a offers answers, so it has no next step of its own.",
                        "Step b hands over to a menu, so it has no answers and no next step.",
                        "Step b opens the menu shop, which no server offers.",
                        "Step c says nothing in one of its languages.",
                        "Step c goes on to gone, which is no step.",
                        "Step d offers 4 answers, at most 3."),
                dialogue.problems(Set.of("objectives")));
    }
}
