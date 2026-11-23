package eu.nordtal.season.packbuilder.map;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/**
 * Draws the top view of an area from BlueMap's low-resolution tiles, once for each number of blocks per pixel.
 *
 * Each pixel averages the colour and height of its blocks; the height shades it as light from the north-west.
 */
public final class MapRender {

    /** The blocks a level-1 tile covers along each side, one block a pixel. */
    static final int TILE = 500;
    /** The first row of a tile's lower half, which holds light and height for the colour above it. */
    static final int META = TILE + 1;
    /** The strongest darkening or brightening of a slope. */
    private static final double SHADE = 0.35;

    private MapRender() {}

    /** The colour and height sums of one image, filled tile by tile so the whole area never sits in memory. */
    private static final class Sums {

        private final int scale;
        private final int width;
        private final int[] red;
        private final int[] green;
        private final int[] blue;
        private final int[] height;
        private final int[] count;

        Sums(final int scale, final int size) {
            this.scale = scale;
            this.width = size / scale;
            final int pixels = width * width;
            red = new int[pixels];
            green = new int[pixels];
            blue = new int[pixels];
            height = new int[pixels];
            count = new int[pixels];
        }

        void add(final int x, final int z, final int rgb, final int blockHeight) {
            final int pixel = (z / scale) * width + x / scale;
            red[pixel] += rgb >> 16 & 0xFF;
            green[pixel] += rgb >> 8 & 0xFF;
            blue[pixel] += rgb & 0xFF;
            height[pixel] += blockHeight;
            count[pixel]++;
        }

        BufferedImage image() {
            final BufferedImage image = new BufferedImage(width, width, BufferedImage.TYPE_INT_ARGB);
            for (int z = 0; z < width; z++) {
                for (int x = 0; x < width; x++) {
                    final int pixel = z * width + x;
                    final int blocks = count[pixel];
                    if (blocks == 0) {
                        continue;
                    }
                    final double north = z > 0 ? meanHeight(pixel - width) : Double.NaN;
                    final double west = x > 0 ? meanHeight(pixel - 1) : Double.NaN;
                    final double here = meanHeight(pixel);
                    final double rise =
                            (here - (Double.isNaN(north) ? here : north)) + (here - (Double.isNaN(west) ? here : west));
                    final double light = 1 + Math.clamp(rise * 0.5 / scale * 0.6, -SHADE, SHADE);
                    image.setRGB(
                            x,
                            z,
                            0xFF000000
                                    | channel(red[pixel], blocks, light) << 16
                                    | channel(green[pixel], blocks, light) << 8
                                    | channel(blue[pixel], blocks, light));
                }
            }
            return image;
        }

        private double meanHeight(final int pixel) {
            return count[pixel] == 0 ? Double.NaN : (double) height[pixel] / count[pixel];
        }

        private static int channel(final int sum, final int blocks, final double light) {
            return (int) Math.min(255, (sum / blocks) * light);
        }
    }

    /**
     * Returns the image of {@code area} for each of {@code scales}, keyed by blocks per pixel.
     * A block no tile covers stays transparent.
     *
     * @param tiles BlueMap's tile folder of the map, which holds level 1 under {@code 1/}
     */
    public static Map<Integer, BufferedImage> draw(final Path tiles, final MapArea area, final List<Integer> scales)
            throws IOException {
        final List<Sums> sums =
                scales.stream().map(scale -> new Sums(scale, area.size())).toList();
        final int[] colour = new int[TILE * TILE];
        final int[] meta = new int[TILE * TILE];
        for (int tz = Math.floorDiv(area.minZ(), TILE); tz <= Math.floorDiv(area.maxZ(), TILE); tz++) {
            for (int tx = Math.floorDiv(area.minX(), TILE); tx <= Math.floorDiv(area.maxX(), TILE); tx++) {
                final Path file = tiles.resolve("1").resolve(path("x", tx)).resolve(path("z", tz) + ".png");
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                final BufferedImage tile = ImageIO.read(file.toFile());
                if (tile == null || tile.getWidth() < TILE || tile.getHeight() < META + TILE) {
                    throw new IOException("not a BlueMap low-resolution tile: " + file);
                }
                tile.getRGB(0, 0, TILE, TILE, colour, 0, TILE);
                tile.getRGB(0, META, TILE, TILE, meta, 0, TILE);
                for (int z = 0; z < TILE; z++) {
                    final int bz = tz * TILE + z - area.minZ();
                    if (bz < 0 || bz >= area.size()) {
                        continue;
                    }
                    for (int x = 0; x < TILE; x++) {
                        final int bx = tx * TILE + x - area.minX();
                        final int rgb = colour[z * TILE + x];
                        if (bx < 0 || bx >= area.size() || rgb >>> 24 == 0) {
                            continue;
                        }
                        final int blockHeight = height(meta[z * TILE + x]);
                        for (final Sums image : sums) {
                            image.add(bx, bz, rgb, blockHeight);
                        }
                    }
                }
            }
        }
        final Map<Integer, BufferedImage> images = new TreeMap<>();
        for (final Sums image : sums) {
            images.put(image.scale, image.image());
        }
        return images;
    }

    /** Returns the height a pixel of the lower half holds in green and blue, read as BlueMap's web app reads it. */
    static int height(final int meta) {
        final int stored = (meta >> 8 & 0xFF) << 8 | meta & 0xFF;
        return stored >= 0x8000 ? stored - 0xFFFF : stored;
    }

    /** Returns BlueMap's folder path of a tile coordinate, one folder a digit: {@code x19} is {@code x1/9}. */
    static String path(final String axis, final int value) {
        final String digits = Integer.toString(value);
        final int first = digits.startsWith("-") ? 2 : 1;
        final StringBuilder path = new StringBuilder(axis).append(digits, 0, first);
        for (int index = first; index < digits.length(); index++) {
            path.append('/').append(digits.charAt(index));
        }
        return path.toString();
    }
}
