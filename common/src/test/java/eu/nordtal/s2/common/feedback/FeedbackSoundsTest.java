package eu.nordtal.s2.common.feedback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that make a wrong sound harmless.
 *
 * <p>None of this needs a server, which is exactly why it is worth pinning here: the behaviour these
 * cases describe only ever shows up on a real one, at the moment somebody has mistyped a key in a
 * production {@code config.yml} - and then the difference between "that chime is missing" and "the
 * server is stopping" is the whole of it.
 */
class FeedbackSoundsTest {

    @Test
    @DisplayName("an empty key silences that category and complains about nothing")
    void anEmptyKeyIsTheEscapeHatch() {
        final List<String> problems = new ArrayList<>();
        final FeedbackSounds sounds = FeedbackSounds.parse(Map.of(
                Feedback.SELECT, new FeedbackSound("", 1.0f, 1.0f),
                Feedback.REFUSED, new FeedbackSound("   ", 1.0f, 1.0f),
                Feedback.TRAVEL, new FeedbackSound("minecraft:block.beacon.power_select", 1.0f, 1.0f)),
                problems::add);

        assertTrue(sounds.isSilent(Feedback.SELECT));
        assertTrue(sounds.isSilent(Feedback.REFUSED));
        assertFalse(sounds.isSilent(Feedback.TRAVEL));
        assertEquals(List.of(), problems,
                "blanking a key is how an operator switches a category off. Complaining about it"
                        + " would train them to ignore the console, which is where the complaints"
                        + " that matter go");
    }

    @Test
    @DisplayName("a category nobody declared is silent")
    void anUndeclaredCategoryIsSilent() {
        final FeedbackSounds sounds = FeedbackSounds.parse(new EnumMap<>(Feedback.class), problem -> {
            throw new AssertionError("nothing to complain about: " + problem);
        });
        for (final Feedback category : Feedback.values()) {
            assertTrue(sounds.isSilent(category), category + " should be silent");
        }
    }

    @Test
    @DisplayName("a key that is not a namespaced key is reported once and silences its category")
    void aMalformedKeyIsReportedAndSilenced() {
        final List<String> problems = new ArrayList<>();
        final FeedbackSounds sounds = FeedbackSounds.parse(
                Map.of(Feedback.SELECT, new FeedbackSound("UI_BUTTON_CLICK", 1.0f, 1.0f)),
                problems::add);

        assertTrue(sounds.isSilent(Feedback.SELECT));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("UI_BUTTON_CLICK"),
                "the complaint has to name the value, or nobody can find it in the file");
    }

    /**
     * The custom-sound case, and the one that must <b>not</b> be treated as an error.
     *
     * <p>A key in our own namespace names a sound that only exists once the resource pack is
     * applied. The server has never heard of it and never will; the client either plays it or does
     * not. Refusing it here would make the pack's own sounds unusable, which is half the reason the
     * config carries keys rather than enum constants.
     */
    @Test
    @DisplayName("a key the server has never heard of is kept, because that is a pack sound")
    void aPackSoundIsNotAnError() {
        final List<String> problems = new ArrayList<>();
        final FeedbackSounds sounds = FeedbackSounds.parse(
                Map.of(Feedback.NETWORK_EVENT, new FeedbackSound("nordtal:milestone.fanfare", 1.0f, 1.0f)),
                problems::add);

        assertEquals(List.of(), problems);
        assertEquals("nordtal:milestone.fanfare", sounds.sound(Feedback.NETWORK_EVENT).key());
    }

    @Test
    @DisplayName("a pitch outside what a client plays is clamped, not refused")
    void anImpossiblePitchIsClamped() {
        final List<String> problems = new ArrayList<>();
        final FeedbackSounds sounds = FeedbackSounds.parse(Map.of(
                Feedback.SELECT, new FeedbackSound("minecraft:ui.button.click", 1.0f, 9.0f),
                Feedback.LOSS, new FeedbackSound("minecraft:entity.villager.no", 1.0f, 0.01f)),
                problems::add);

        assertEquals(FeedbackSound.MAX_PITCH, sounds.sound(Feedback.SELECT).pitch());
        assertEquals(FeedbackSound.MIN_PITCH, sounds.sound(Feedback.LOSS).pitch());
        assertEquals(2, problems.size(), problems.toString());
    }

    @Test
    @DisplayName("a nonsense volume falls back rather than silencing")
    void aNonsenseVolumeFallsBack() {
        final List<String> problems = new ArrayList<>();
        final FeedbackSounds sounds = FeedbackSounds.parse(
                Map.of(Feedback.SELECT, new FeedbackSound("minecraft:ui.button.click", -3.0f, 1.0f)),
                problems::add);

        assertNotNull(sounds.sound(Feedback.SELECT));
        assertEquals(FeedbackSound.DEFAULT_VOLUME, sounds.sound(Feedback.SELECT).volume());
        assertEquals(1, problems.size(), problems.toString());
    }

    /**
     * The other half of "never an exception on a player path".
     *
     * <p>A sound that throws once throws every time, so the adapter reports it once and the category
     * goes quiet. The alternative is that stack trace per click, which is what turns one bad config
     * value into an unreadable log.
     */
    @Test
    @DisplayName("a category that failed to play is reported once and then stays silent")
    void aFailureIsReportedOnce() {
        final List<String> problems = new ArrayList<>();
        final FeedbackSounds sounds = FeedbackSounds.parse(
                Map.of(Feedback.SELECT, new FeedbackSound("minecraft:ui.button.click", 1.0f, 1.0f)),
                problems::add);

        assertTrue(sounds.failed(Feedback.SELECT, new IllegalStateException("boom"), problems::add));
        assertFalse(sounds.failed(Feedback.SELECT, new IllegalStateException("boom"), problems::add));
        assertNull(sounds.sound(Feedback.SELECT));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("minecraft:ui.button.click"));
    }

    @Test
    @DisplayName("silent() is silent everywhere")
    void silentIsSilentEverywhere() {
        final FeedbackSounds sounds = FeedbackSounds.silent();
        for (final Feedback category : Feedback.values()) {
            assertNull(sounds.sound(category), category.name());
        }
    }
}
