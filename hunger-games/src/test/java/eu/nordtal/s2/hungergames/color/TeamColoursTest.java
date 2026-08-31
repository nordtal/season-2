package eu.nordtal.s2.hungergames.color;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamColoursTest {

    @Test
    void generatesExactlyTheRequestedCount() {
        final List<Integer> palette = TeamColours.generatePalette(12);
        assertEquals(12, palette.size());
    }

    @Test
    void everyGeneratedColourIsDistinct() {
        final List<Integer> palette = TeamColours.generatePalette(30);
        final Set<Integer> distinct = new HashSet<>(palette);
        assertEquals(30, distinct.size());
    }

    @Test
    void aSinglePersonGameStillGetsOneColour() {
        assertEquals(1, TeamColours.generatePalette(1).size());
    }

    @Test
    void refusesNonPositiveCount() {
        assertThrows(IllegalArgumentException.class, () -> TeamColours.generatePalette(0));
        assertThrows(IllegalArgumentException.class, () -> TeamColours.generatePalette(-1));
    }

    @Test
    void pureRedMapsToRed() {
        assertEquals("RED", TeamColours.nearestNamedColour(0xFF5555));
    }

    @Test
    void pureWhiteMapsToWhite() {
        assertEquals("WHITE", TeamColours.nearestNamedColour(0xFFFFFF));
    }

    @Test
    void pureBlackMapsToBlack() {
        assertEquals("BLACK", TeamColours.nearestNamedColour(0x000000));
    }

    @Test
    void everyGeneratedColourResolvesToSomeNamedColour() {
        for (final Integer colour : TeamColours.generatePalette(16)) {
            final String named = TeamColours.nearestNamedColour(colour);
            assertTrue(named != null && !named.isBlank());
        }
    }
}
