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
        assertEquals(Band.base(64) + Look.FIELD.height(), Band.head(64, Look.FIELD));
    }

    @Test
    void highAboveTheWallTheBandReachesFromItsGroundToAboveTheFeet() {
        assertEquals(61, Band.foot(64, 144));
        assertEquals(151, Band.head(144, Look.FIELD));
    }

    @Test
    void onHighGroundBesideTheWallTheBandStillReachesDownToTheWallsGround() {
        assertEquals(61, Band.foot(64, 90));
        assertEquals(97, Band.head(90, Look.FIELD));
    }

    @Test
    void withoutItsGroundThePanelFollowsTheFeet() {
        assertEquals(141, Band.foot(Band.UNKNOWN, 144));
    }

    @Test
    void inACaveBelowTheWallsGroundTheBandCoversBothFeetAndGround() {
        final Band.Range need = Band.need(List.of(new Band.Seen("border/field", 64)), 30 + Band.EYE, Look.FIELD);

        assertEquals(Band.foot(Band.UNKNOWN, 30 - Band.CAMERA), need.bottom(), 1e-9);
        assertEquals(Band.head(64, Look.FIELD), need.top(), 1e-9);
    }

    @Test
    void inAValleyBesideAHillTheRangeCoversTheHillsTerrainBand() {
        final Band.Range need = Band.need(
                List.of(new Band.Seen("border/field", 64), new Band.Seen("border/field", 85)),
                64 + Band.EYE,
                Look.FIELD);

        assertEquals(Band.foot(64, 64 - Band.CAMERA), need.bottom(), 1e-9);
        assertEquals(Band.head(85, Look.FIELD), need.top(), 1e-9);
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
