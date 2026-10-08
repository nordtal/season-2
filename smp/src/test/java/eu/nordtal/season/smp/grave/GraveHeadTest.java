package eu.nordtal.season.smp.grave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * The skull on a grave, held against the vanilla geometry of a player head in an item display with {@code NONE}.
 *
 * In the display's own frame the head's hat layer hangs below the origin: 8.5 pixels wide, from a quarter pixel above
 * the origin to 8.25 pixels below it. The chest's lid is 14 pixels wide and 14 pixels high.
 */
class GraveHeadTest {

    private static final float PIXEL = 1f / 16f;
    private static final float HAT_HALF_WIDTH = 4.25f * PIXEL;
    private static final float HAT_TOP = 0.25f * PIXEL;
    private static final float HAT_BOTTOM = -8.25f * PIXEL;
    private static final float LID_HALF_WIDTH = 7f * PIXEL;
    private static final float LID_TOP = 14f * PIXEL;
    private static final float TOLERANCE = 1e-5f;

    @Test
    void everyCornerOfTheLeanedHeadStaysWithinTheLidOutline() {
        for (final Vector3f corner : drawnCorners()) {
            assertTrue(
                    Math.abs(corner.x) <= LID_HALF_WIDTH && Math.abs(corner.z) <= LID_HALF_WIDTH,
                    "a corner of the skull reaches past the lid, at " + corner);
        }
    }

    @Test
    void theHeadsCentreStaysOverTheChestsMiddle() {
        final Vector3f centre = drawn(new Vector3f(0f, (HAT_TOP + HAT_BOTTOM) / 2f, 0f));
        assertEquals(0f, centre.x, TOLERANCE, "the lean swung the skull sideways");
        assertEquals(0f, centre.z, TOLERANCE, "the lean swung the skull sideways");
    }

    @Test
    void theHeadDipsIntoTheLidAndMostOfItShows() {
        final List<Vector3f> corners = drawnCorners();
        final float bottom =
                (float) corners.stream().mapToDouble(c -> c.y).min().orElseThrow();
        final float top = (float) corners.stream().mapToDouble(c -> c.y).max().orElseThrow();
        assertTrue(bottom < LID_TOP, "the skull sits on the lid instead of in it, its bottom at " + bottom);
        assertTrue(
                top - LID_TOP >= (top - bottom) / 2f,
                "less than half the skull shows above the lid: " + (top - LID_TOP) + " of " + (top - bottom));
    }

    @Test
    void theHeadLeansSlightly() {
        final Transformation transformation = GraveHead.transformation();
        final float lean = (float) Math.toDegrees(new AxisAngle4f(transformation.getLeftRotation()).angle
                + new AxisAngle4f(transformation.getRightRotation()).angle);
        assertTrue(lean > 5f && lean <= 30f, "the skull leans " + lean + " degrees, not slightly");
    }

    /** The eight corners of the hat layer where the display draws them, relative to the chest's bottom centre. */
    private static List<Vector3f> drawnCorners() {
        final List<Vector3f> corners = new ArrayList<>(8);
        for (final float x : new float[] {-HAT_HALF_WIDTH, HAT_HALF_WIDTH}) {
            for (final float y : new float[] {HAT_BOTTOM, HAT_TOP}) {
                for (final float z : new float[] {-HAT_HALF_WIDTH, HAT_HALF_WIDTH}) {
                    corners.add(drawn(new Vector3f(x, y, z)));
                }
            }
        }
        return corners;
    }

    /** A point of the model as the display draws it: translation, left rotation, scale, right rotation. */
    private static Vector3f drawn(final Vector3f point) {
        final Transformation transformation = GraveHead.transformation();
        return transformation
                .getLeftRotation()
                .transform(transformation
                        .getRightRotation()
                        .transform(new Vector3f(point))
                        .mul(transformation.getScale()))
                .add(transformation.getTranslation());
    }
}
