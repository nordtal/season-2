package eu.nordtal.season.smp.grave;

import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Computes the transformation of the skull display of a grave. The skull sinks into the chest lid and leans back. */
final class GraveHead {

    /** The height of the vanilla chest model in blocks, which is the top of its lid. */
    private static final float CHEST_HEIGHT = 0.875f;

    /**
     * The centre of the skull in an {@code ItemDisplay} with {@code NONE}.
     * The origin of the display is the top of the head, so the centre is 0.25 below it.
     */
    private static final Vector3fc CENTRE = new Vector3f(0f, -0.25f, 0f);

    /** The depth in blocks that the bottom of the upright skull sinks below the top of the lid. */
    private static final float SINK_DEPTH = 0.15f;

    /**
     * The lean angle of the skull about the X axis through its centre.
     * At any angle the hat layer stays within 0.376 blocks of the centre on X and Z, inside the 0.4375 of the lid.
     */
    private static final float LEAN_DEGREES = 20f;

    /** The target position of the skull centre above the middle of the chest. */
    private static final Vector3fc TARGET = new Vector3f(0f, CHEST_HEIGHT - SINK_DEPTH + 0.25f, 0f);

    private GraveHead() {}

    /** Returns the lean rotation and a translation that moves the skull centre to {@link #TARGET}. */
    static Transformation transformation() {
        final Quaternionf lean = new Quaternionf().rotationX((float) Math.toRadians(LEAN_DEGREES));
        // The rotation turns the display about its origin, so the translation subtracts the rotated centre.
        final Vector3f translation = new Vector3f(TARGET).sub(lean.transform(new Vector3f(CENTRE)));
        return new Transformation(translation, lean, new Vector3f(1f, 1f, 1f), new Quaternionf());
    }
}
