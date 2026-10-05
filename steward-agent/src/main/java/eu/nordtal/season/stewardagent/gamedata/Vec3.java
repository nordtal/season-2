package eu.nordtal.season.stewardagent.gamedata;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.jspecify.annotations.Nullable;

/** A point or a direction in a model's space, where y is up and z points at the viewer. */
record Vec3(double x, double y, double z) {

    static final Vec3 ZERO = new Vec3(0, 0, 0);

    /** Three numbers of a model file, {@code absent} for each one it leaves out. */
    static Vec3 of(final @Nullable JsonElement element, final double absent) {
        final double[] values = {absent, absent, absent};
        if (element instanceof JsonArray array) {
            for (int i = 0; i < Math.min(3, array.size()); i++) {
                values[i] = array.get(i).getAsDouble();
            }
        }
        return new Vec3(values[0], values[1], values[2]);
    }

    Vec3 plus(final Vec3 other) {
        return new Vec3(x + other.x, y + other.y, z + other.z);
    }

    Vec3 minus(final Vec3 other) {
        return new Vec3(x - other.x, y - other.y, z - other.z);
    }

    Vec3 times(final double factor) {
        return new Vec3(x * factor, y * factor, z * factor);
    }

    Vec3 times(final Vec3 factors) {
        return new Vec3(x * factors.x, y * factors.y, z * factors.z);
    }

    Vec3 cross(final Vec3 other) {
        return new Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x);
    }

    double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    Vec3 normalised() {
        final double length = length();
        return length == 0 ? this : times(1 / length);
    }

    Vec3 rotateX(final double degrees) {
        final double radians = Math.toRadians(degrees);
        final double cos = Math.cos(radians);
        final double sin = Math.sin(radians);
        return new Vec3(x, y * cos - z * sin, y * sin + z * cos);
    }

    Vec3 rotateY(final double degrees) {
        final double radians = Math.toRadians(degrees);
        final double cos = Math.cos(radians);
        final double sin = Math.sin(radians);
        return new Vec3(x * cos + z * sin, y, -x * sin + z * cos);
    }

    Vec3 rotateZ(final double degrees) {
        final double radians = Math.toRadians(degrees);
        final double cos = Math.cos(radians);
        final double sin = Math.sin(radians);
        return new Vec3(x * cos - y * sin, x * sin + y * cos, z);
    }

    /** Rotated by the unit quaternion {@code (qx, qy, qz, qw)}, as a model's transformation names one. */
    Vec3 rotate(final double qx, final double qy, final double qz, final double qw) {
        // v' = v + 2w(q x v) + 2(q x (q x v))
        final Vec3 q = new Vec3(qx, qy, qz);
        final Vec3 twice = q.cross(this).times(2);
        return plus(twice.times(qw)).plus(q.cross(twice));
    }
}
