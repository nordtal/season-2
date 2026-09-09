package eu.nordtal.s2.common.stage;

import net.kyori.adventure.text.Component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The arithmetic of a staging: which picture appears on which tick, and how long the whole thing is.
 *
 * <p>Separate from {@link CinematicsTest} on purpose. This is the part that is a pure function of
 * the description - it needs no scheduler, no surface and no player - and it is the part a wrong
 * answer in is invisible: a sequence whose second frame lands one tick early looks like a sequence.
 */
class CinematicTest {

    private static final Component A = Component.text("a");
    private static final Component B = Component.text("b");
    private static final Component C = Component.text("c");

    @Test
    @DisplayName("the frames run in the order they were added, each starting where the last ended")
    void theCuesAreCumulative() {
        final Cinematic cinematic = Cinematic.builder()
                .frame(A, 5)
                .frame(B, 10)
                .frame(C, 1)
                .build();

        assertEquals(List.of(0, 5, 15),
                cinematic.cues().stream().map(Cinematic.Cue::atTick).toList(),
                "a frame starts when the one before it has finished, and the first starts at zero");
        assertEquals(List.of(A, B, C),
                cinematic.cues().stream().map(cue -> cue.frame().image()).toList(),
                "the order is the order they were added; a set or a map here would lose it");
        assertEquals(16, cinematic.totalTicks());
    }

    @Test
    @DisplayName("frames of equal length are the ordinary animated case")
    void framesOfEqualLength() {
        final Cinematic cinematic = Cinematic.builder().frames(List.of(A, B, C), 4).build();

        assertEquals(List.of(0, 4, 8),
                cinematic.cues().stream().map(Cinematic.Cue::atTick).toList());
        assertEquals(12, cinematic.totalTicks());
    }

    @Test
    @DisplayName("a staging with no frames is refused rather than run as an empty effect")
    void aStagingNeedsAtLeastOneFrame() {
        // Without this the effect is applied, nothing is drawn, and the player stands blind and
        // silent for the length of a sequence that does not exist. Failing here fails at the
        // moment somebody wrote it.
        assertThrows(IllegalArgumentException.class, () -> Cinematic.builder().build());
    }

    @Test
    @DisplayName("a frame nobody could see is refused")
    void aFrameLastsAtLeastOneTick() {
        assertThrows(IllegalArgumentException.class,
                () -> Cinematic.builder().frame(A, 0).build());
        assertThrows(IllegalArgumentException.class,
                () -> Cinematic.builder().frame(A, -1).build());
    }

    @Test
    @DisplayName("the three optional parts really are optional")
    void everythingButTheFramesIsOptional() {
        final Cinematic bare = Cinematic.builder().frame(A, 1).build();

        assertNull(bare.subtitle());
        assertNull(bare.effect());
        assertNull(bare.sound(), "a staging with no sound is silent, which is what a moment whose"
                + " sound has not been drawn yet has to be");
    }

    @Test
    @DisplayName("an effect with no name is refused; leaving it out is how you say there is none")
    void anEffectNeedsAKey() {
        assertThrows(IllegalArgumentException.class, () -> new Cinematic.Effect("", 0));
        assertThrows(IllegalArgumentException.class, () -> new Cinematic.Effect(null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Cinematic.Effect("minecraft:blindness", -1));
    }
}
