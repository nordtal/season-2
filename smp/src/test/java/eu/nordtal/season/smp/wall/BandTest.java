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
    void onTheGroundThePanelsCoverEveryHeightABandCanStandAt() {
        for (final int[] world : new int[][] {{-64, 320}, {0, 256}}) {
            final Band.Range range = Band.range(world[1], 0);

            assertEquals(Band.GROUNDED, range.top() - range.bottom());
            assertTrue(range.bottom() <= world[0] - Band.CAMERA - 2 - Band.BELOW);
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
        final double farthest = Math.hypot(Band.anchor(320) + 64 + Band.CAMERA, Band.FAR + 8);
        assertTrue(Band.viewRange(-64, 320) * 64 * 0.5 >= farthest);
    }

    @Test
    void aPlayerIsSentTheFieldFromAfarAndThePixelLookFromNearOnly() {
        assertTrue(sent(Look.FIELD, 120.5));
        assertFalse(sent(Look.FIELD, 140.5));
        assertTrue(sent(Look.PIXEL, 29.5));
        assertFalse(sent(Look.PIXEL, 30.5));
    }

    /** Whether a player at 150, 0 is sent the panel {@code along} blocks away. */
    private static boolean sent(final Look look, final double along) {
        return Band.seen(LONG, look, 150, 0, panel -> new Band.Ground(64, 0)).keySet().stream()
                .anyMatch(panel -> Math.abs(panel.z() - Wall.INSET) < 1e-9 && Math.abs(panel.x() - 150 - along) < 1e-9);
    }

    @Test
    void theTintCarriesTheGroundAndTheShaderReadsItBack() {
        for (final int foot : new int[] {Band.LOWEST, -1, 0, 63, 64, 319, Band.HIGHEST}) {
            for (final int rise : new int[] {0, 1, 31, 32, Band.HIGHEST_RISE}) {
                final Band.Ground ground = new Band.Ground(foot, rise);
                final int rgb = Band.tint(ground);
                assertEquals(ground, decodeGround(rgb), "ground " + ground);
                assertEquals(ground, decodeGround(rgb + 0x010101), "high " + ground);
                assertEquals(ground, decodeGround(rgb - 0x010101), "low " + ground);
            }
        }
    }

    @Test
    void aGroundBeyondWhatTheTintCarriesIsSentAsTheNearestItCarries() {
        assertEquals(
                new Band.Ground(Band.HIGHEST, Band.HIGHEST_RISE), decodeGround(Band.tint(new Band.Ground(2000, 90))));
        assertEquals(new Band.Ground(Band.LOWEST, 0), decodeGround(Band.tint(new Band.Ground(-100, -1))));
    }

    @Test
    void anUnknownGroundEncodesAsWhite() {
        assertEquals(0xFFFFFF, Band.tint(Band.Ground.UNKNOWN));
        assertEquals(Band.Ground.UNKNOWN, decodeGround(Band.tint(Band.Ground.UNKNOWN)));
        assertEquals(Band.Ground.UNKNOWN, decodeGround(Band.tint(Band.Ground.UNKNOWN) - 0x010101));
    }

    /** What item.vsh reads from the tint for the ground. */
    private static Band.Ground decodeGround(final int rgb) {
        final int rRaw = (rgb >> 16) & 255;
        final int gRaw = (rgb >> 8) & 255;
        final int bRaw = rgb & 255;
        if (rRaw >= 254 && gRaw >= 254 && bRaw >= 254) {
            return Band.Ground.UNKNOWN;
        }
        final int r = (rRaw - 128) >> 2;
        final int g = (gRaw - 128) >> 2;
        final int b = (bRaw - 128) >> 2;
        final int value = r << 10 | g << 5 | b;
        return new Band.Ground((value >> 6) + Band.LOWEST, value & 63);
    }
}
