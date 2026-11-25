package eu.nordtal.season.smp.wall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BandTest {

    /** A wall along z 0 from x 0 to x 300. */
    private static final Wall LONG = new Wall(List.of(WallTest.area(0, 0, 300, 0, 300, 10, 0, 10)));

    @Test
    void onTheWallsGroundTheBandStandsWhereItStoodBefore() {
        assertEquals(Band.base(64), Band.foot(64, 64));
    }

    @Test
    void highAboveTheWallTheBandReachesFromItsGroundToAboveTheFeet() {
        assertEquals(61, Band.foot(64, 144));
    }

    @Test
    void onHighGroundBesideTheWallTheBandStillReachesDownToTheWallsGround() {
        assertEquals(61, Band.foot(64, 90));
    }

    @Test
    void withoutItsGroundThePanelFollowsTheFeet() {
        assertEquals(141, Band.foot(Band.UNKNOWN, 144));
    }

    @Test
    void onTheGroundThePanelsCoverEveryHeightABandCanStandAt() {
        for (final int[] world : new int[][] {{-64, 320}, {0, 256}}) {
            final Band.Range range = Band.range(world[1], 0);

            assertEquals(Band.GROUNDED, range.top() - range.bottom());
            assertTrue(range.bottom() <= Band.foot(Band.UNKNOWN, world[0] - Band.CAMERA - Band.EYE));
            assertTrue(range.top() >= world[1] + Band.CAMERA + Look.FIELD.height());
        }
    }

    @Test
    void aStretchKeepsThePanelsUpperEndAndSinksTheirLowerEnd() {
        assertEquals(new Band.Range(320 + Band.TOP - Band.SKYWARD, 320 + Band.TOP), Band.range(320, 1));
        assertEquals(Band.range(320, 0).top(), Band.range(320, 0.5).top());
        assertEquals(
                (Band.GROUNDED + Band.SKYWARD) / 2,
                Band.range(320, 0.5).top() - Band.range(320, 0.5).bottom());
    }

    @Test
    void theWallRisesForAFlyingPlayerAtEveryHeight() {
        assertTrue(Band.skyward(false, true, false, free -> false));
        assertTrue(Band.skyward(false, true, true, free -> false));
    }

    @Test
    void theWallRisesOnceFourBlocksBelowAreFreeAndSinksOnceThreeAreNot() {
        assertFalse(Band.skyward(false, false, false, free -> free <= 3.5));
        assertTrue(Band.skyward(false, false, false, free -> free <= 4));
        assertTrue(Band.skyward(true, false, false, free -> free <= 3.5));
        assertFalse(Band.skyward(true, false, false, free -> free <= 2.5));
    }

    @Test
    void theWallStaysOnTheGroundForAPlayerInWaterOrOnAClimb() {
        assertFalse(Band.skyward(false, false, true, free -> true));
        assertFalse(Band.skyward(true, false, true, free -> true));
    }

    @Test
    void theEntitiesStandAboveTheWorldsBlocksAndAreDrawnFromItsBottom() {
        assertTrue(Band.anchor(320) >= 321);
        final double farthest = Math.hypot(Band.anchor(320) + 64 + Band.CAMERA, Band.fadeEnd(500) + 8);
        assertTrue(Band.viewRange(-64, 320) * 64 * 0.5 >= farthest);
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
                .anyMatch(panel -> Math.abs(panel.z() - Wall.INSET) < 1e-9 && Math.abs(panel.x() - 150 - along) < 1e-9);
    }

    @Test
    void theFieldReachesNoFartherThanAtItsDeepest() {
        assertEquals(Band.fadeEnd(Band.LEVEL + Band.DEPTH), Band.fadeEnd(500));
        assertEquals(Band.RADIUS, Band.fadeEnd(Band.LEVEL));
    }

    @Test
    void theTintCarriesTheGroundAndTheShaderReadsItBack() {
        for (final int ground : new int[] {-64, -1, 0, 63, 64, 319, 2000}) {
            final int rgb = Band.tint(ground);
            assertEquals(ground, decodeGround(rgb), "ground " + ground);
            assertEquals(ground, decodeGround(rgb + 0x010101), "high " + ground);
            assertEquals(ground, decodeGround(rgb - 0x010101), "low " + ground);
        }
    }

    @Test
    void anUnknownGroundEncodesAsWhite() {
        assertEquals(0xFFFFFF, Band.tint(Band.UNKNOWN));
        assertEquals(Band.UNKNOWN, decodeGround(Band.tint(Band.UNKNOWN)));
        assertEquals(Band.UNKNOWN, decodeGround(Band.tint(Band.UNKNOWN) - 0x010101));
    }

    /** What item.vsh reads from the tint for the ground height. */
    private static int decodeGround(final int rgb) {
        final int rRaw = (rgb >> 16) & 255;
        final int gRaw = (rgb >> 8) & 255;
        final int bRaw = rgb & 255;
        if (rRaw >= 254 && gRaw >= 254 && bRaw >= 254) {
            return Band.UNKNOWN;
        }
        final int r = (rRaw - 128) >> 2;
        final int g = (gRaw - 128) >> 2;
        final int b = (bRaw - 128) >> 2;
        return (r << 10 | g << 5 | b) - 2048;
    }
}
