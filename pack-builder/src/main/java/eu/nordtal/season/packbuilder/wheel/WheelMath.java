package eu.nordtal.season.packbuilder.wheel;

import eu.nordtal.season.packbuilder.json.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 4 x 4 matrices, row-major, for the moves that the wheel's item definitions carry. */
final class WheelMath {

    private WheelMath() {}

    static double[] identity() {
        final double[] m = new double[16];
        for (int i = 0; i < 16; i += 5) {
            m[i] = 1.0;
        }
        return m;
    }

    static double[] translate(final double x, final double y, final double z) {
        final double[] m = identity();
        m[3] = x;
        m[7] = y;
        m[11] = z;
        return m;
    }

    static double[] scale(final double x, final double y, final double z) {
        final double[] m = new double[16];
        m[0] = x;
        m[5] = y;
        m[10] = z;
        m[15] = 1.0;
        return m;
    }

    static double[] rotateX(final double a) {
        final double[] m = identity();
        m[5] = Math.cos(a);
        m[6] = -Math.sin(a);
        m[9] = Math.sin(a);
        m[10] = Math.cos(a);
        return m;
    }

    static double[] rotateY(final double a) {
        final double[] m = identity();
        m[0] = Math.cos(a);
        m[2] = Math.sin(a);
        m[8] = -Math.sin(a);
        m[10] = Math.cos(a);
        return m;
    }

    static double[] rotateZ(final double a) {
        final double[] m = identity();
        m[0] = Math.cos(a);
        m[1] = -Math.sin(a);
        m[4] = Math.sin(a);
        m[5] = Math.cos(a);
        return m;
    }

    /** The product of {@code ms}, left to right. */
    static double[] times(final double[]... ms) {
        double[] out = ms[0];
        for (int i = 1; i < ms.length; i++) {
            out = multiply(out, ms[i]);
        }
        return out;
    }

    private static double[] multiply(final double[] a, final double[] b) {
        final double[] out = new double[16];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                double s = 0.0;
                for (int k = 0; k < 4; k++) {
                    s += a[r * 4 + k] * b[k * 4 + c];
                }
                out[r * 4 + c] = s;
            }
        }
        return out;
    }

    /** The inverse by Gauss-Jordan elimination; the display transforms are invertible, their scales never zero. */
    static double[] inverse(final double[] m) {
        final double[] a = m.clone();
        final double[] inv = identity();
        for (int col = 0; col < 4; col++) {
            int pivot = col;
            for (int r = col + 1; r < 4; r++) {
                if (Math.abs(a[r * 4 + col]) > Math.abs(a[pivot * 4 + col])) {
                    pivot = r;
                }
            }
            if (pivot != col) {
                for (int c = 0; c < 4; c++) {
                    swap(a, col * 4 + c, pivot * 4 + c);
                    swap(inv, col * 4 + c, pivot * 4 + c);
                }
            }
            final double p = a[col * 4 + col];
            if (p == 0.0) {
                throw new IllegalArgumentException("a display transform without an inverse");
            }
            for (int c = 0; c < 4; c++) {
                a[col * 4 + c] /= p;
                inv[col * 4 + c] /= p;
            }
            for (int r = 0; r < 4; r++) {
                if (r == col) {
                    continue;
                }
                final double f = a[r * 4 + col];
                if (f == 0.0) {
                    continue;
                }
                for (int c = 0; c < 4; c++) {
                    a[r * 4 + c] -= f * a[col * 4 + c];
                    inv[r * 4 + c] -= f * inv[col * 4 + c];
                }
            }
        }
        return inv;
    }

    private static void swap(final double[] m, final int i, final int j) {
        final double t = m[i];
        m[i] = m[j];
        m[j] = t;
    }

    /** A quaternion, as {@code [x, y, z, w]} or {@code {angle, axis}}, as a rotation matrix. */
    static double[] quaternion(final Object q) {
        final double x;
        final double y;
        final double z;
        final double w;
        if (q instanceof final Map<?, ?> map) {
            final List<Double> axis = doubles(map.get("axis"));
            double n2 = 0.0;
            for (final double v : axis) {
                n2 += v * v;
            }
            final double n = Math.sqrt(n2);
            final double h = ((Number) map.get("angle")).doubleValue() / 2;
            x = axis.get(0) / n * Math.sin(h);
            y = axis.get(1) / n * Math.sin(h);
            z = axis.get(2) / n * Math.sin(h);
            w = Math.cos(h);
        } else {
            final List<Double> v = doubles(q);
            x = v.get(0);
            y = v.get(1);
            z = v.get(2);
            w = v.get(3);
        }
        return new double[] {
            1 - 2 * (y * y + z * z),
            2 * (x * y - z * w),
            2 * (x * z + y * w),
            0.0,
            2 * (x * y + z * w),
            1 - 2 * (x * x + z * z),
            2 * (y * z - x * w),
            0.0,
            2 * (x * z - y * w),
            2 * (y * z + x * w),
            1 - 2 * (x * x + y * y),
            0.0,
            0.0,
            0.0,
            0.0,
            1.0
        };
    }

    /** A node's {@code transformation}: 16 numbers row-major, or translation, rotations and scale. */
    static double[] transformation(final Object t) {
        if (t instanceof final List<?> list) {
            final double[] m = new double[16];
            for (int i = 0; i < 16; i++) {
                m[i] = ((Number) list.get(i)).doubleValue();
            }
            return m;
        }
        final Map<?, ?> m = (Map<?, ?>) t;
        final List<Double> tr = doubles(m.get("translation"));
        final List<Double> sc = doubles(m.get("scale"));
        return times(
                translate(tr.get(0), tr.get(1), tr.get(2)),
                quaternion(required(m, "left_rotation")),
                scale(sc.get(0), sc.get(1), sc.get(2)),
                quaternion(required(m, "right_rotation")));
    }

    /** The matrix as the JSON list a definition carries, every number to 4 places. */
    static List<Object> list(final double[] m) {
        final List<Object> out = new ArrayList<>(m.length);
        for (final double v : m) {
            out.add(Json.rounded(v));
        }
        return out;
    }

    static List<Double> doubles(final Object list) {
        final List<Double> out = new ArrayList<>();
        for (final Object v : (List<?>) list) {
            out.add(((Number) v).doubleValue());
        }
        return out;
    }

    private static Object required(final Map<?, ?> map, final String key) {
        final Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("a transformation without " + key);
        }
        return value;
    }
}
