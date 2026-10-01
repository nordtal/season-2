package eu.nordtal.s2.smp.welcome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.smp.stage.Cinematic;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

/** What the season's opening moment shows while its art has not arrived. */
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
}
