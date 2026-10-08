package eu.nordtal.season.discordbot.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** What a member who has just chosen in the onboarding channel is answered. */
class AnswerTest {

    @Test
    void aLanguageChosenWithoutARegionAsksForTheRegion() {
        assertEquals(Answer.ASK_REGION, Answer.after(true, false, true));
    }

    @Test
    void aLanguageChosenWhileARegionIsHeldIsSavedAtOnce() {
        assertEquals(Answer.SAVED, Answer.after(true, true, true));
    }

    @Test
    void aRegionChosenIsSaved() {
        assertEquals(Answer.SAVED, Answer.after(true, true, false));
    }

    @Test
    void aChoiceThatCouldNotBeGivenFails() {
        assertEquals(Answer.FAILED, Answer.after(false, false, true));
        assertEquals(Answer.FAILED, Answer.after(false, true, true));
    }

    @Test
    void aLanguageWithNoRegionToOfferFailsRatherThanAskingWithAnEmptyMenu() {
        assertEquals(Answer.FAILED, Answer.after(true, false, false));
    }
}
