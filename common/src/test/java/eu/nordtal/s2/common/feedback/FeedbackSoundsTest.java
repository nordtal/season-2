package eu.nordtal.s2.common.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pins the rules that make a mistyped sound key harmless rather than an error on a player path. */
class FeedbackSoundsTest {

    @Test
    void anEmptyKeySilencesThatCategoryAndComplainsAboutNothing() {
        final List<String> problems = new ArrayList<>();
        final FeedbackSounds sounds = FeedbackSounds.parse(
                Map.of(
                        Feedback.SELECT, new FeedbackSound("", 1.0f, 1.0f),
                        Feedback.REFUSED, new FeedbackSound("   ", 1.0f, 1.0f),
                        Feedback.TRAVEL, new FeedbackSound("minecraft:block.beacon.power_select", 1.0f, 1.0f)),
                problems::add);

        assertTrue(sounds.isSilent(Feedback.SELECT));
        assertTrue(sounds.isSilent(Feedback.REFUSED));
        assertFalse(sounds.isSilent(Feedback.TRAVEL));
        assertEquals(
                List.of(),
                problems,
                "blanking a key is how an operator switches a category off. Complaining about it"
                        + " would train them to ignore the console, which is where the complaints"
                        + " that matter go");
    }

    @Test
    void aCategoryNobodyDeclaredIsSilent() {
        final FeedbackSounds sounds = FeedbackSounds.parse(new EnumMap<>(Feedback.class), problem -> {
            throw new AssertionError("nothing to complain about: " + problem);
        });
        for (final Feedback category : Feedback.values()) {
            assertTrue(sounds.isSilent(category), category + " should be silent");
        }
    }

    @Test
    void aKeyThatIsNotANamespacedKeyIsReportedOnceAndSilencesItsCategory() {
        final List<String> problems = new ArrayList<>();
        final FeedbackSounds sounds = FeedbackSounds.parse(
                Map.of(Feedback.SELECT, new FeedbackSound("UI_BUTTON_CLICK", 1.0f, 1.0f)), problems::add);

        assertTrue(sounds.isSilent(Feedback.SELECT));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(
                problems.getFirst().contains("UI_BUTTON_CLICK"),
                "the complaint has to name the value, or nobody can find it in the file");
    }

    /** Checks that a key in our own namespace is accepted, since it names a sound only the pack defines. */
    @Test
    void aKeyTheServerHasNeverHeardOfIsKeptBecauseThatIsAPackSound() {
        final List<String> problems = new ArrayList<>();
        final FeedbackSounds sounds = FeedbackSounds.parse(
                Map.of(Feedback.NETWORK_EVENT, new FeedbackSound("nordtal:milestone.fanfare", 1.0f, 1.0f)),
                problems::add);

        assertEquals(List.of(), problems);
        assertEquals(
                "nordtal:milestone.fanfare",
                sounds.sound(Feedback.NETWORK_EVENT).key());
    }

    @Test
    void aPitchOutsideWhatAClientPlaysIsClampedNotRefused() {
        final List<String> problems = new ArrayList<>();
        final FeedbackSounds sounds = FeedbackSounds.parse(
                Map.of(
                        Feedback.SELECT, new FeedbackSound("minecraft:ui.button.click", 1.0f, 9.0f),
                        Feedback.LOSS, new FeedbackSound("minecraft:entity.villager.no", 1.0f, 0.01f)),
                problems::add);

        assertEquals(FeedbackSound.MAX_PITCH, sounds.sound(Feedback.SELECT).pitch());
        assertEquals(FeedbackSound.MIN_PITCH, sounds.sound(Feedback.LOSS).pitch());
        assertEquals(2, problems.size(), problems.toString());
    }

    @Test
    void aNonsenseVolumeFallsBackRatherThanSilencing() {
        final List<String> problems = new ArrayList<>();
        final FeedbackSounds sounds = FeedbackSounds.parse(
                Map.of(Feedback.SELECT, new FeedbackSound("minecraft:ui.button.click", -3.0f, 1.0f)), problems::add);

        assertNotNull(sounds.sound(Feedback.SELECT));
        assertEquals(FeedbackSound.DEFAULT_VOLUME, sounds.sound(Feedback.SELECT).volume());
        assertEquals(1, problems.size(), problems.toString());
    }

    /** Checks that a sound that throws is reported once and its category goes quiet. */
    @Test
    void aCategoryThatFailedToPlayIsReportedOnceAndThenStaysSilent() {
        final List<String> problems = new ArrayList<>();
        final FeedbackSounds sounds = FeedbackSounds.parse(
                Map.of(Feedback.SELECT, new FeedbackSound("minecraft:ui.button.click", 1.0f, 1.0f)), problems::add);

        assertTrue(sounds.failed(Feedback.SELECT, new IllegalStateException("boom"), problems::add));
        assertFalse(sounds.failed(Feedback.SELECT, new IllegalStateException("boom"), problems::add));
        assertNull(sounds.sound(Feedback.SELECT));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("minecraft:ui.button.click"));
    }

    @Test
    void silentIsSilentEverywhere() {
        final FeedbackSounds sounds = FeedbackSounds.silent();
        for (final Feedback category : Feedback.values()) {
            assertNull(sounds.sound(category), category.name());
        }
    }
}
