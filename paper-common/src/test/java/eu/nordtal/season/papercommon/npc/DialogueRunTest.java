package eu.nordtal.season.papercommon.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.npc.Dialogue;
import eu.nordtal.season.database.npc.Dialogue.Answer;
import eu.nordtal.season.database.npc.Dialogue.Step;
import eu.nordtal.season.database.npc.Dialogue.Words;
import eu.nordtal.season.packrendering.npc.DialogueScreen;
import eu.nordtal.season.packrendering.npc.DialogueScreen.Hint;
import eu.nordtal.season.packrendering.npc.DialogueScreen.Mark;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The dialogue tick by tick: opening, typing, holds, answers, the hand-off, its switch and the soft end. */
class DialogueRunTest {

    private static final Feel FEEL = new Feel(30, 0.8, 3.0, 120);
    private static final Labels LABELS =
            new Labels("Next", "Choose", "Answer", "Don't show again", Map.of("objectives", "Open objectives"));

    /** The tick the panel first shows and typing begins: the fourth stage, two ticks each. */
    private static final int FIRST_TYPING_TICK = 7;

    @Test
    void theBarsOpenInFourStagesOfTwoTicksAndThePanelShowsAtTheLast() {
        final DialogueRun run = play(speaking("start", "Hello.", null));
        final List<Frame> frames = ticks(run, 8);

        assertEquals(
                List.of(1, 1, 2, 2, 3, 3, 4, 4),
                frames.stream().map(Frame::bars).toList());
        assertEquals(
                List.of(1, 1, 2, 2, 3, 3, 4, 4),
                frames.stream().map(Frame::vignette).toList());
        assertTrue(frames.get(0).effects().contains(sound(Effect.Cue.OPEN)));
        assertNull(frames.get(5).panel());
        assertTrue(frames.get(5).hints().isEmpty());
        assertNotNull(frames.get(6).panel());
        assertEquals(List.of(new Hint(Mark.NEXT, "Next")), frames.get(6).hints());
        assertEquals(0.5, frames.get(3).zoom(), 1e-9);
        assertEquals(1.0, frames.get(7).zoom(), 1e-9);
    }

    @Test
    void typingPausesAfterAFullStop() {
        // H 33, i 67, . 100 ms, then the space waits 280 ms more: 413, Y 447, o 480.
        final DialogueRun run = play(speaking("start", "Hi. Yo", null));
        ticks(run, FIRST_TYPING_TICK - 1);

        assertEquals("", line(run.tick(Where.STILL)));
        ticks(run, 1);
        assertEquals("Hi.", line(run.tick(Where.STILL)));
        ticks(run, 5);
        assertEquals("Hi.", line(run.tick(Where.STILL)), "400 ms in, the pause still runs");
        assertEquals("Hi. Y", line(run.tick(Where.STILL)), "450 ms in");
    }

    @Test
    void aTypedTextHoldsWithItsMarkBlinkingThenGoesOn() {
        // "Hello" is typed 167 ms in, the fourth tick, and holds 1200 + 5 * 40 ms, 28 ticks.
        final DialogueRun run = play(speaking("start", "Hello", "two"), speaking("two", "Bye", null));
        final List<Frame> frames = ticks(run, 40);
        final int typed = FIRST_TYPING_TICK + 4;

        assertFalse(panel(frames, typed - 1).holdMark(), "no mark while typing");
        assertTrue(panel(frames, typed).holdMark());
        assertFalse(panel(frames, typed + 10).holdMark(), "the mark blinks off after 10 ticks");
        assertTrue(panel(frames, typed + 20).holdMark());
        assertFalse(at(frames, typed + 27).effects().contains(sound(Effect.Cue.NEXT_TEXT)));
        assertTrue(at(frames, typed + 28).effects().contains(sound(Effect.Cue.NEXT_TEXT)));
    }

    @Test
    void aRightClickFinishesTheTextAndTheNextGoesOn() {
        final DialogueRun run = play(speaking("start", "A longer text", "two"), speaking("two", "Bye", null));
        ticks(run, FIRST_TYPING_TICK + 1);

        run.rightClick();
        assertEquals("A longer text", line(run.tick(Where.STILL)));
        run.rightClick();
        assertEquals("A longer text", line(run.tick(Where.STILL)), "a click within 4 ticks of the last counts not");
        ticks(run, 2);
        run.rightClick();
        assertTrue(run.tick(Where.STILL).effects().contains(sound(Effect.Cue.NEXT_TEXT)));
    }

    @Test
    void answersAppearAQuarterSecondAfterTheQuestionAndALeftClickCountsAQuarterSecondLater() {
        // "Ok?" is typed 100 ms in, the second tick.
        final DialogueRun run = play(
                question("start", "Ok?", answer("Two", "two"), answer("Three", "three")),
                speaking("two", "Two", null),
                speaking("three", "Three", null));
        final int typed = FIRST_TYPING_TICK + 2;
        final List<Frame> frames = ticks(run, typed + 4);

        assertTrue(panel(frames, typed + 4).answers().isEmpty());
        assertFalse(run.answering());
        final Frame shown = run.tick(Where.STILL);
        assertEquals(List.of("Two!", "Three!"), shown.panel().answers());
        assertTrue(shown.effects().contains(new Effect.HoldSlot()));
        assertEquals(List.of(new Hint(Mark.CHOOSE, "Choose"), new Hint(Mark.ANSWER, "Answer")), shown.hints());

        run.leftClick();
        ticks(run, 4);
        run.leftClick();
        assertTrue(run.answering(), "a left-click within 250 ms of the answers counts not");
        run.tick(Where.STILL);
        run.leftClick();
        final Frame taken = run.tick(Where.STILL);
        assertTrue(taken.effects().contains(sound(Effect.Cue.ANSWER)));
        assertTrue(taken.effects().contains(new Effect.ReleaseSlot()));
        assertTrue(taken.effects().contains(sound(Effect.Cue.NEXT_TEXT)));
        assertFalse(run.answering());
    }

    @Test
    void theWheelAndTheKeysMoveTheSelectionAndTheLeftClickTakesIt() {
        final DialogueRun run = play(
                question("start", "Pick", answer("A", "a"), answer("B", "b"), answer("C", "c")),
                speaking("a", "Picked A", null),
                speaking("b", "Picked B", null),
                speaking("c", "Picked C", null));
        assertFalse(run.select(5), "before the answers show, the hotbar is the player's");
        ticks(run, 30);

        assertTrue(run.select(Hotbar.CHOICE_SLOT + 1));
        assertEquals(1, run.tick(Where.STILL).panel().selected());
        run.select(Hotbar.CHOICE_SLOT + 1);
        run.select(Hotbar.CHOICE_SLOT + 1);
        final Frame bottom = run.tick(Where.STILL);
        assertEquals(2, bottom.panel().selected());
        assertEquals(
                1,
                bottom.effects().stream()
                        .filter(sound(Effect.Cue.SELECTION)::equals)
                        .count());
        run.select(Hotbar.CHOICE_SLOT - 1);
        assertEquals(1, run.tick(Where.STILL).panel().selected());
        run.select(0);
        assertEquals(0, run.tick(Where.STILL).panel().selected());
        run.select(2);
        assertEquals(2, run.tick(Where.STILL).panel().selected());

        run.leftClick();
        run.tick(Where.STILL);
        final Frame next = ticks(run, 30).getLast();
        assertEquals("Picked C", next.panel().lines().getFirst());
    }

    @Test
    void anAnswerWithoutANextStepClosesSoftly() {
        final DialogueRun run = play(question("start", "Pick", answer("End", null)));
        ticks(run, 30);
        run.leftClick();
        final List<Frame> frames = ticks(run, 6);

        assertTrue(frames.get(0).effects().contains(sound(Effect.Cue.CLOSE)));
        assertNull(frames.get(0).panel());
        assertEquals(List.of(3, 2, 1, 0, 0, 0), frames.stream().map(Frame::bars).toList());
        assertEquals(
                List.of(3, 2, 1, 0, 0, 0), frames.stream().map(Frame::vignette).toList());
        assertFalse(frames.get(4).effects().contains(new Effect.End()));
        assertTrue(frames.get(5).effects().contains(new Effect.End()));
        assertTrue(run.over());
    }

    @Test
    void aStepThatGoesNowhereEndsAfterItsHold() {
        final DialogueRun run = play(speaking("start", "Bye", null));
        final List<Frame> frames = ticks(run, 60);

        assertTrue(frames.stream().anyMatch(frame -> frame.effects().contains(sound(Effect.Cue.CLOSE))));
        assertTrue(run.over());
    }

    @Test
    void aNextStepThatIsMissingEndsSoftly() {
        final DialogueRun run = play(speaking("start", "Hello", "gone"));
        ticks(run, 60);

        assertTrue(run.over());
    }

    @Test
    void theHandOffOpensItsMenuByItselfAfterThreeSeconds() {
        // "Go." is typed 100 ms in and holds 3 s, 60 ticks.
        final DialogueRun run = play(handOff("start", "Go."));
        final int typed = FIRST_TYPING_TICK + 2;
        final List<Frame> frames = ticks(run, typed + 59);

        assertEquals(
                List.of(new Hint(Mark.NEXT, "Open objectives")),
                frames.getLast().hints());
        assertEquals(
                new DialogueScreen.Switch("Don't show again", false),
                frames.getLast().toggle());
        assertTrue(frames.stream().noneMatch(DialogueRunTest::handsOff));
        final Frame handedOff = run.tick(Where.STILL);
        assertEquals(List.of(new Effect.HandOff("objectives", SkipChoice.Change.NONE)), handOffs(handedOff));
        assertEquals(0, handedOff.bars(), "the bars go on the tick the menu opens");
        assertNull(handedOff.panel());

        final List<Frame> fade = ticks(run, 6);
        assertEquals(
                List.of(3, 2, 1, 0, 0, 0), fade.stream().map(Frame::vignette).toList());
        assertTrue(fade.getLast().effects().contains(new Effect.End()));
        assertFalse(fade.stream().anyMatch(frame -> frame.effects().contains(sound(Effect.Cue.CLOSE))));
    }

    @Test
    void aRightClickAtTheHandOffOpensTheMenuAtOnce() {
        final DialogueRun run = play(handOff("start", "Go."));
        ticks(run, FIRST_TYPING_TICK + 3);

        run.rightClick();
        assertTrue(handsOff(run.tick(Where.STILL)));
    }

    @Test
    void shiftTicksTheBoxAndHoldsTheHandOffAnew() {
        final DialogueRun run = play(handOff("start", "Go."));
        ticks(run, 30);

        run.sneak(true);
        final Frame ticked = run.tick(Where.STILL);
        assertTrue(ticked.effects().contains(sound(Effect.Cue.SWITCH_ON)));
        assertEquals(new DialogueScreen.Switch("Don't show again", true), ticked.toggle());
        final List<Frame> held = ticks(run, 58);
        assertTrue(held.stream().noneMatch(DialogueRunTest::handsOff), "the hold starts again with the press");
        assertEquals(
                List.of(new Effect.HandOff("objectives", SkipChoice.Change.SKIP)), handOffs(run.tick(Where.STILL)));
    }

    @Test
    void aSecondPressUnticksTheBox() {
        final DialogueRun run = play(handOff("start", "Go."));
        ticks(run, 20);
        run.sneak(true);
        run.sneak(false);
        run.sneak(true);

        final Frame frame = run.tick(Where.STILL);
        assertTrue(frame.effects().contains(sound(Effect.Cue.SWITCH_OFF)));
        assertFalse(frame.toggle().ticked());
    }

    @Test
    void aShiftHeldFromTheReplayChangesNothing() {
        final DialogueRun run = new DialogueRun(
                dialogue(handOff("start", "Go.")), "Ada", Locale.ENGLISH, FEEL, LABELS, "objectives", true, true);
        ticks(run, 20);
        run.sneak(true);

        assertTrue(run.tick(Where.STILL).toggle().ticked(), "the replay shows the box ticked");
        final Frame handedOff = runUntilHandOff(run);
        assertEquals(List.of(new Effect.HandOff("objectives", SkipChoice.Change.NONE)), handOffs(handedOff));
    }

    @Test
    void aNewPressAfterTheReplayUnticksTheBoxAndUnskips() {
        final DialogueRun run = new DialogueRun(
                dialogue(handOff("start", "Go.")), "Ada", Locale.ENGLISH, FEEL, LABELS, "objectives", true, true);
        ticks(run, 20);
        run.sneak(false);
        run.sneak(true);

        assertFalse(run.tick(Where.STILL).toggle().ticked());
        assertEquals(
                List.of(new Effect.HandOff("objectives", SkipChoice.Change.UNSKIP)), handOffs(runUntilHandOff(run)));
    }

    @Test
    void aShiftPressedBeforeTheHandOffCountsNotThere() {
        final DialogueRun run = play(speaking("start", "Hello", "two"), handOff("two", "Go."));
        ticks(run, 12);
        run.sneak(true);
        final List<Frame> before = ticks(run, 30);

        assertTrue(before.stream().noneMatch(frame -> frame.effects().contains(sound(Effect.Cue.SWITCH_ON))));
        assertEquals(List.of(new Effect.HandOff("objectives", SkipChoice.Change.NONE)), handOffs(runUntilHandOff(run)));
    }

    @Test
    void aStaleSkipIsUnskippedAtTheHandOff() {
        final DialogueRun run = new DialogueRun(
                dialogue(handOff("start", "Go.")), "Ada", Locale.ENGLISH, FEEL, LABELS, "shop", false, false);

        assertEquals(
                List.of(new Effect.HandOff("objectives", SkipChoice.Change.UNSKIP)), handOffs(runUntilHandOff(run)));
    }

    @Test
    void walkingAwayEndsTheDialogueSoftly() {
        final DialogueRun run = play(speaking("start", "A long text that keeps typing for a while", null));
        ticks(run, 10);

        assertTrue(run.tick(new Where(2.9, 0)).panel() != null, "within the end distance it goes on");
        final Frame away = run.tick(new Where(3.1, 0));
        assertTrue(away.effects().contains(sound(Effect.Cue.CLOSE)));
        assertNull(away.panel());
        assertFalse(run.listening());
    }

    @Test
    void turningAwayEndsTheDialogueSoftly() {
        final DialogueRun run = play(speaking("start", "A long text that keeps typing for a while", null));
        ticks(run, 10);

        assertTrue(run.tick(new Where(0, 120)).panel() != null);
        assertTrue(run.tick(new Where(0, 121)).effects().contains(sound(Effect.Cue.CLOSE)));
    }

    @Test
    void theTypingSoundPlaysWhileATextTypes() {
        final List<Frame> frames = ticks(play(speaking("start", "Hello there", null)), 20);

        assertTrue(frames.stream().anyMatch(frame -> frame.effects().contains(sound(Effect.Cue.TYPE))));
    }

    @Test
    void theTextIsInTheReadersLanguage() {
        final DialogueRun run = new DialogueRun(
                dialogue(speaking("start", "Hello", null)), "Ada", Locale.GERMAN, FEEL, LABELS, null, false, false);
        final Frame frame = ticks(run, 20).getLast();

        assertEquals("Hello!", frame.panel().lines().getFirst());
    }

    private static DialogueRun play(final Step... steps) {
        return new DialogueRun(dialogue(steps), "Ada", Locale.ENGLISH, FEEL, LABELS, null, false, false);
    }

    private static Dialogue dialogue(final Step... steps) {
        return new Dialogue(steps[0].id(), List.of(steps));
    }

    /** A step whose German text is its English one with an exclamation mark. */
    private static Step speaking(final String id, final String text, final @Nullable String next) {
        return new Step(id, 0, 0, new Words(text, text + "!"), List.of(), next, null);
    }

    private static Step question(final String id, final String text, final Answer... answers) {
        return new Step(id, 0, 0, new Words(text, text), List.of(answers), null, null);
    }

    private static Answer answer(final String text, final @Nullable String next) {
        return new Answer(new Words(text + "!", text + "?"), next);
    }

    private static Step handOff(final String id, final String text) {
        return new Step(id, 0, 0, new Words(text, text), List.of(), null, "objectives");
    }

    private static List<Frame> ticks(final DialogueRun run, final int count) {
        final List<Frame> frames = new ArrayList<>();
        for (int tick = 0; tick < count; tick++) {
            frames.add(run.tick(Where.STILL));
        }
        return frames;
    }

    private static Frame runUntilHandOff(final DialogueRun run) {
        for (int tick = 0; tick < 400; tick++) {
            final Frame frame = run.tick(Where.STILL);
            if (handsOff(frame)) {
                return frame;
            }
        }
        throw new AssertionError("the dialogue never handed off");
    }

    private static boolean handsOff(final Frame frame) {
        return !handOffs(frame).isEmpty();
    }

    private static List<Effect> handOffs(final Frame frame) {
        return frame.effects().stream()
                .filter(effect -> effect instanceof Effect.HandOff)
                .toList();
    }

    /** Returns the frame of the 1-based {@code tick}. */
    private static Frame at(final List<Frame> frames, final int tick) {
        return frames.get(tick - 1);
    }

    private static DialogueScreen.Panel panel(final List<Frame> frames, final int tick) {
        final DialogueScreen.Panel panel = at(frames, tick).panel();
        assertNotNull(panel, "tick " + tick + " shows a panel");
        return panel;
    }

    private static String line(final Frame frame) {
        final DialogueScreen.Panel panel = frame.panel();
        assertNotNull(panel);
        return panel.lines().getFirst();
    }

    private static Effect sound(final Effect.Cue cue) {
        return new Effect.Sound(cue);
    }
}
