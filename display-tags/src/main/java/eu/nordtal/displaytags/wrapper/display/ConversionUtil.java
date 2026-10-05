package eu.nordtal.displaytags.wrapper.display;

import com.github.retrooper.packetevents.util.Vector3f;
import org.bukkit.util.Vector;

public class ConversionUtil {
    public static Vector3f fromBukkitVector(final Vector vector) {
        return new Vector3f((float) vector.getX(), (float) vector.getY(), (float) vector.getZ());
    }
}
