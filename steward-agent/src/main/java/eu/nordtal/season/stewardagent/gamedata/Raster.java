package eu.nordtal.season.stewardagent.gamedata;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * Draws textured quads into one icon, nearest pixel first, at four times its size and averaged down.
 *
 * A point is in model pixels centred on the icon: x right, y up, z towards the viewer, sixteen across.
 */
final class Raster {

    /** The side of one icon in the sheet. */
    static final int ICON = 32;

    /** How many samples a side of one output pixel takes, which smooths the edges of a turned block. */
    private static final int SAMPLES = 4;

    private static final int SIZE = ICON * SAMPLES;

    private final int[] colours = new int[SIZE * SIZE];
    private final double[] depths = new double[SIZE * SIZE];

    Raster() {
        Arrays.fill(depths, Double.NEGATIVE_INFINITY);
    }

    /**
     * Draws one quad; its corners in order, each with the texture coordinate it shows, in sixteenths.
     *
     * @param texture the whole texture, of which an animated one's first square frame is used
     * @param tint what the texture's colour is multiplied by, white for none
     * @param shade how much light the face gets, from 0 to 1
     */
    void quad(
            final Vec3[] corners,
            final double[][] uv,
            final BufferedImage texture,
            final int tint,
            final double shade) {
        triangle(corners, uv, 0, 1, 2, texture, tint, shade);
        triangle(corners, uv, 0, 2, 3, texture, tint, shade);
    }

    /** Draws a texture flat over the whole icon, as a generated item's layer is. */
    void flat(final BufferedImage texture, final int tint) {
        final int width = texture.getWidth();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                final int texel = texel(texture, (x + 0.5) / SIZE * 16, (y + 0.5) / SIZE * 16, width);
                blend(y * SIZE + x, tinted(texel, tint, 1), Double.MAX_VALUE);
            }
        }
    }

    /** The icon, each pixel the average of its samples weighted by how opaque they are. */
    BufferedImage image() {
        final BufferedImage image = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < ICON; y++) {
            for (int x = 0; x < ICON; x++) {
                long alpha = 0;
                long red = 0;
                long green = 0;
                long blue = 0;
                for (int sy = 0; sy < SAMPLES; sy++) {
                    for (int sx = 0; sx < SAMPLES; sx++) {
                        final int colour = colours[(y * SAMPLES + sy) * SIZE + x * SAMPLES + sx];
                        final int a = colour >>> 24;
                        alpha += a;
                        red += (long) ((colour >> 16) & 0xFF) * a;
                        green += (long) ((colour >> 8) & 0xFF) * a;
                        blue += (long) (colour & 0xFF) * a;
                    }
                }
                if (alpha == 0) {
                    continue;
                }
                final int a = (int) (alpha / (SAMPLES * SAMPLES));
                image.setRGB(
                        x,
                        y,
                        (a << 24) | (int) (red / alpha) << 16 | (int) (green / alpha) << 8 | (int) (blue / alpha));
            }
        }
        return image;
    }

    private void triangle(
            final Vec3[] corners,
            final double[][] uv,
            final int a,
            final int b,
            final int c,
            final BufferedImage texture,
            final int tint,
            final double shade) {
        final double scale = SIZE / 16.0;
        final double ax = (corners[a].x() + 8) * scale;
        final double ay = (8 - corners[a].y()) * scale;
        final double bx = (corners[b].x() + 8) * scale;
        final double by = (8 - corners[b].y()) * scale;
        final double cx = (corners[c].x() + 8) * scale;
        final double cy = (8 - corners[c].y()) * scale;
        final double area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (Math.abs(area) < 1e-9) {
            return;
        }
        final int minX = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))));
        final int maxX = Math.min(SIZE - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
        final int minY = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy))));
        final int maxY = Math.min(SIZE - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
        final int width = texture.getWidth();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                final double px = x + 0.5;
                final double py = y + 0.5;
                final double wa = ((bx - px) * (cy - py) - (by - py) * (cx - px)) / area;
                final double wb = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) / area;
                final double wc = 1 - wa - wb;
                if (wa < -1e-9 || wb < -1e-9 || wc < -1e-9) {
                    continue;
                }
                final double depth = wa * corners[a].z() + wb * corners[b].z() + wc * corners[c].z();
                final double u = wa * uv[a][0] + wb * uv[b][0] + wc * uv[c][0];
                final double v = wa * uv[a][1] + wb * uv[b][1] + wc * uv[c][1];
                blend(y * SIZE + x, tinted(texel(texture, u, v, width), tint, shade), depth);
            }
        }
    }

    /** An opaque sample in front replaces what is there; a see-through one is laid over it. */
    private void blend(final int at, final int colour, final double depth) {
        final int alpha = colour >>> 24;
        if (alpha == 0 || depth < depths[at]) {
            return;
        }
        if (alpha == 0xFF) {
            colours[at] = colour;
            depths[at] = depth;
            return;
        }
        colours[at] = over(colour, colours[at]);
    }

    private static int over(final int top, final int bottom) {
        final double ta = (top >>> 24) / 255.0;
        final double ba = (bottom >>> 24) / 255.0;
        final double a = ta + ba * (1 - ta);
        if (a == 0) {
            return 0;
        }
        final int r = (int) Math.round((((top >> 16) & 0xFF) * ta + ((bottom >> 16) & 0xFF) * ba * (1 - ta)) / a);
        final int g = (int) Math.round((((top >> 8) & 0xFF) * ta + ((bottom >> 8) & 0xFF) * ba * (1 - ta)) / a);
        final int b = (int) Math.round(((top & 0xFF) * ta + (bottom & 0xFF) * ba * (1 - ta)) / a);
        return ((int) Math.round(a * 255) << 24) | (r << 16) | (g << 8) | b;
    }

    /** The texel at {@code (u, v)} in sixteenths of the first square frame. */
    private static int texel(final BufferedImage texture, final double u, final double v, final int width) {
        final int frame = Math.min(width, texture.getHeight());
        final int x = Math.clamp((long) Math.floor(u / 16 * width), 0, width - 1);
        final int y = Math.clamp((long) Math.floor(v / 16 * frame), 0, frame - 1);
        return texture.getRGB(x, y);
    }

    private static int tinted(final int colour, final int tint, final double shade) {
        final int r = (int) Math.round(((colour >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255.0 * shade);
        final int g = (int) Math.round(((colour >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255.0 * shade);
        final int b = (int) Math.round((colour & 0xFF) * (tint & 0xFF) / 255.0 * shade);
        return (colour & 0xFF000000) | (r << 16) | (g << 8) | b;
    }
}
