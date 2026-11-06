package eu.nordtal.season.smp.welcome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.smp.stage.Cinematic;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

class SeasonWelcomeTest {

    @Test
    void thePicturesAreVisiblyUnfinished() {
        for (final Cinematic.Frame frame : SeasonWelcome.cinematic().frames()) {
            assertTrue(
                    frame.image() instanceof TextComponent text
                            && text.content().contains("placeholder"),
                    "a frame no longer announces itself as a placeholder. If the art has arrived, hold that the"
                            + " frames name their font instead: a nordtal: code point without one draws another"
                            + " font's glyph");
        }
    }

    @Test
    void theBlindnessLastsAsLongAsThePictures() {
        assertEquals(
                new Cinematic.Effect("minecraft:blindness", 0),
                SeasonWelcome.cinematic().effect(),
                "without blindness the pictures are not the only thing on the screen");
    }

    @Test
    void aSpawnOutsideTheBorderIsRefused() {
        assertFalse(
                SeasonWelcome.within(100, 200, 20, 100, 211),
                "a first join outside the border kills the player in the same tick");
        assertFalse(SeasonWelcome.within(100, 200, 20, -3000, 200), "far outside on x");
    }

    @Test
    void aSpawnInsideTheBorderIsKept() {
        assertTrue(SeasonWelcome.within(100, 200, 20, 96.5, 205.5), "inside");
        assertTrue(SeasonWelcome.within(100, 200, 20, 110, 190), "on the edge");
    }
}
