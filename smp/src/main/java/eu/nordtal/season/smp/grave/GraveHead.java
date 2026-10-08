package eu.nordtal.season.smp.grave;

import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Where a grave's skull sits: dipped into the chest's lid, leaned back about its own centre.
 *
 * A display draws translation, left rotation, scale, right rotation, and both rotations turn about its origin.
 */
final class GraveHead {

    /** The vanilla chest model's height in blocks, drawn at native size: the top of its lid. */
    private static final float CHEST_HEIGHT = 0.875f;

    /**
     * The skull's centre in an {@code ItemDisplay} with {@code NONE}, which hangs the head below the origin.
     * NONE shifts the item's unit cube by -0.5, and the player_head item model stands the 0.5 high head on that cube's
     * floor, so the origin is the top of the head. A rotation about the origin swings this centre by 0.25 sin(angle).
     */
    private static final Vector3fc CENTRE = new Vector3f(0f, -0.25f, 0f);

    /** How far the bottom of the upright skull dips below the top of the lid. */
    private static final float SINK_DEPTH = 0.15f;

    /**
     * How far the skull leans back, about the X axis through its centre.
     * Turned about one axis through its centre, the 0.53 wide hat layer spans at most its face diagonal, 0.376 from the
     * centre on X and Z, inside the lid's 0.4375 at any angle; a second axis would reach the space diagonal, 0.46.
     */
    private static final float LEAN_DEGREES = 20f;

    /** Where the skull's centre ends up: over the chest's middle, where the dipped upright head has it. */
    private static final Vector3fc TARGET = new Vector3f(0f, CHEST_HEIGHT - SINK_DEPTH + 0.25f, 0f);

    private GraveHead() {}

    /** The skull display's transformation: the lean, and a translation that puts the leaned centre on the target. */
    static Transformation transformation() {
        final Quaternionf lean = new Quaternionf().rotationX((float) Math.toRadians(LEAN_DEGREES));
        // The target minus where the lean puts the centre, so the lean turns the head in place instead of swinging it.
        final Vector3f translation = new Vector3f(TARGET).sub(lean.transform(new Vector3f(CENTRE)));
        return new Transformation(translation, lean, new Vector3f(1f, 1f, 1f), new Quaternionf());
    }
}
