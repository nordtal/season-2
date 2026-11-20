package eu.nordtal.season.smp.wall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BandTest {

    /** A wall along z 0 from x 0 to x 300. */
    private static final Wall LONG = new Wall(List.of(WallTest.area(0, 0, 300, 0, 300, 10, 0, 10)));

    @Test
    void onTheWallsGroundTheBandStandsWhereItStoodBefore() {
        assertEquals(Band.base(64), Band.foot(64, 64));
        assertEquals(Band.base(64) + Look.FIELD.height(), Band.head(64, 64, Look.FIELD));
    }

    @Test
    void highAboveTheWallTheBandReachesFromItsGroundToAboveTheFeet() {
        assertEquals(61, Band.foot(64, 144));
        assertEquals(151, Band.head(64, 144, Look.FIELD));
    }

    @Test
    void onHighGroundBesideTheWallTheBandStillReachesDownToTheWallsGround() {
        assertEquals(61, Band.foot(64, 90));
        assertEquals(97, Band.head(64, 90, Look.FIELD));
    }

    @Test
    void withoutItsGroundThePanelFollowsTheFeet() {
        assertEquals(141, Band.foot(Band.UNKNOWN, 144));
        assertEquals(151, Band.head(Band.UNKNOWN, 144, Look.FIELD));
    }

    @Test
    void aPlayerHighUpIsSentTheWallFarAlongTheGround() {
        assertTrue(sent(64 + 80, 90.5));
        assertFalse(sent(64 + Band.EYE, 90.5));
        assertTrue(sent(64 + Band.EYE, 40.5));
    }

    /** Whether a player at 150, 0 with the eye at {@code eyeY} is sent the panel {@code along} blocks away. */
    private static boolean sent(final double eyeY, final double along) {
        return Band.seen(LONG, Look.FIELD, 150, 0, eyeY, panel -> 64).keySet().stream()
                .anyMatch(panel -> panel.z() == 0 && Math.abs(panel.x() - 150 - along) < 1e-9);
    }

    @Test
    void theFieldReachesNoFartherThanAtItsDeepest() {
        assertEquals(Band.fadeEnd(Band.LEVEL + Band.DEPTH), Band.fadeEnd(500));
        assertEquals(Band.RADIUS, Band.fadeEnd(Band.LEVEL));
    }

    @Test
    void thePanelsKeepTheirHeightsWhileTheyCoverTheBandWithRoom() {
        final Band.Range current = new Band.Range(30, 100);

        assertSame(current, Band.fit(current, new Band.Range(50, 80)));
        assertSame(current, Band.fit(current, new Band.Range(42, 88)));
    }

    @Test
    void thePanelsAreStretchedBeforeTheBandReachesTheirEnd() {
        final Band.Range current = new Band.Range(30, 100);

        assertEquals(new Band.Range(17, 113), Band.fit(current, new Band.Range(41, 89)));
    }

    @Test
    void thePanelsShrinkOnceTheyCoverFarMoreThanTheBand() {
        final Band.Range current = new Band.Range(0, 200);

        assertEquals(new Band.Range(26, 104), Band.fit(current, new Band.Range(50, 80)));
    }

    @Test
    void theTintCarriesTheGroundAndTheShaderReadsItBack() {
        for (final int ground : new int[] {-64, -1, 0, 63, 64, 319, 2000}) {
            assertEquals(ground, decode(Band.tint(ground)), "ground " + ground);
            assertEquals(ground, decode(Band.tint(ground) + 0x010101), "a channel one high, ground " + ground);
            assertEquals(ground, decode(Band.tint(ground) - 0x010101), "a channel one low, ground " + ground);
        }
    }

    @Test
    void anUnknownGroundIsWhite() {
        assertEquals(0xFFFFFF, Band.tint(Band.UNKNOWN));
    }

    /** What item.vsh reads from the tint. */
    private static int decode(final int rgb) {
        final int r = (((rgb >> 16) & 255) - 128) >> 2;
        final int g = (((rgb >> 8) & 255) - 128) >> 2;
        final int b = ((rgb & 255) - 128) >> 2;
        return (r << 10 | g << 5 | b) - 2048;
    }
}
