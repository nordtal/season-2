package eu.nordtal.s2.common.stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic of a staging: which picture appears on which tick, and how long the whole thing is.
 *
 * Separate from {@link CinematicsTest} on purpose. This is the part that is a pure function of
 * the description - it needs no scheduler, no surface and no player - and it is the part a wrong
 * answer in is invisible: a sequence whose second frame lands one tick early looks like a sequence.
 */
class CinematicTest {

    private static final Component A = Component.text("a");
    private static final Component B = Component.text("b");
    private static final Component C = Component.text("c");

    @Test
    void theFramesRunInTheOrderTheyWereAddedEachStartingWhereTheLastEnded() {
        final Cinematic cinematic =
                Cinematic.builder().frame(A, 5).frame(B, 10).frame(C, 1).build();

        assertEquals(
                List.of(0, 5, 15),
                cinematic.cues().stream().map(Cinematic.Cue::atTick).toList(),
                "a frame starts when the one before it has finished, and the first starts at zero");
        assertEquals(
                List.of(A, B, C),
                cinematic.cues().stream().map(cue -> cue.frame().image()).toList(),
                "the order is the order they were added; a set or a map here would lose it");
        assertEquals(16, cinematic.totalTicks());
    }

    @Test
    void framesOfEqualLengthAreTheOrdinaryAnimatedCase() {
        final Cinematic cinematic =
                Cinematic.builder().frames(List.of(A, B, C), 4).build();

        assertEquals(
                List.of(0, 4, 8),
                cinematic.cues().stream().map(Cinematic.Cue::atTick).toList());
        assertEquals(12, cinematic.totalTicks());
    }

    @Test
    void aStagingWithNoFramesIsRefusedRatherThanRunAsAnEmptyEffect() {
        // An empty sequence would leave the player blind and silent; it fails when built.
        assertThrows(IllegalArgumentException.class, () -> Cinematic.builder().build());
    }

    @Test
    void aFrameNobodyCouldSeeIsRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Cinematic.builder().frame(A, 0).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> Cinematic.builder().frame(A, -1).build());
    }

    @Test
    void theThreeOptionalPartsReallyAreOptional() {
        final Cinematic bare = Cinematic.builder().frame(A, 1).build();

        assertNull(bare.subtitle());
        assertNull(bare.effect());
        assertNull(
                bare.sound(),
                "a staging with no sound is silent, which is what a moment whose"
                        + " sound has not been drawn yet has to be");
    }

    @Test
    void anEffectWithNoNameIsRefusedLeavingItOutIsHowYouSayThereIsNone() {
        assertThrows(IllegalArgumentException.class, () -> new Cinematic.Effect("", 0));
        assertThrows(IllegalArgumentException.class, () -> new Cinematic.Effect(null, 0));
        assertThrows(IllegalArgumentException.class, () -> new Cinematic.Effect("minecraft:blindness", -1));
    }
}
