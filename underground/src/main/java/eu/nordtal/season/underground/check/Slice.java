package eu.nordtal.season.underground.check;

import eu.nordtal.season.underground.anvil.ChunkBlocks;
import eu.nordtal.season.underground.anvil.Region;
import eu.nordtal.season.underground.anvil.Section;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;

/** Draws a vertical cut through several worlds along the same line, one below the other, as a picture. */
public final class Slice {

    private static final int MINIMUM_WIDTH = 420;
    private static final int LABEL = 26;
    private static final int UNKNOWN = 0xA0A0A0;

    /** A colour per block: a name with = in front is the whole name, any other is a part of it; the first wins. */
    private static final String[] COLOURS = {
        "=air EAF2FF",
        "=cave_air EAF2FF",
        "=void_air EAF2FF",
        "=water 3B6FD6",
        "=bubble_column 3B6FD6",
        "seagrass 3B6FD6",
        "kelp 3B6FD6",
        "=lava FF7A00",
        "=bedrock 000000",
        "diamond_ore 00E5E5",
        "gold_ore FFD800",
        "redstone_ore E00000",
        "lapis_ore 1040D0",
        "emerald_ore 00C040",
        "iron D8A878",
        "copper_ore C8703A",
        "raw_copper C8703A",
        "coal_ore 202020",
        "amethyst A050E0",
        "=calcite A050E0",
        "=smooth_basalt A050E0",
        "sculk 0A3A4A",
        "brick E040C0",
        "planks E040C0",
        "copper E040C0",
        "tiles E040C0",
        "polished E040C0",
        "chiseled E040C0",
        "fence E040C0",
        "rail E040C0",
        "=vault E040C0",
        "spawner E040C0",
        "chest E040C0",
        "cobble E040C0",
        "stairs E040C0",
        "slab E040C0",
        "wall E040C0",
        "=stone 8A8A8A",
        "=andesite 8A8A8A",
        "=deepslate 4A4A52",
        "=tuff 6B6F66",
        "=granite A07868",
        "=diorite BEBEBE",
        "=gravel 9A918A",
        "dirt 7A5A3A",
        "=podzol 7A5A3A",
        "=mud 7A5A3A",
        "=grass_block 5A9A3A",
        "moss 5A9A3A",
        "sand DCCF9A",
        "leaves 2F6B2A",
        "vine 2F6B2A",
        "grass 2F6B2A",
        "fern 2F6B2A",
        "log 5A3A1A",
        "wood 5A3A1A",
        "snow FFFFFF",
        "ice A8D0FF",
        "terracotta B0603A",
        "dripstone 866A5A",
        "sulfur D8D040",
        "cinnabar D8D040",
        "=clay A0A8B8",
        "=obsidian 301840",
        "=magma_block 301840"
    };

    private final Map<String, Integer> known = new HashMap<>();

    /**
     * Where the cut runs and how it is drawn.
     *
     * @param corners the blocks the cut runs through, each as x and z; it is straight between two of them
     * @param lowest the lowest height that is drawn
     * @param highest the highest height that is drawn
     * @param scale the pixels per block
     */
    public record Line(List<int[]> corners, int lowest, int highest, int scale) {

        /** Returns the columns of the cut in their order, each as x and z. */
        private List<int[]> columns() {
            final List<int[]> columns = new ArrayList<>();
            for (int corner = 1; corner < corners.size(); corner++) {
                final int[] from = corners.get(corner - 1);
                final int[] to = corners.get(corner);
                final int steps = Math.max(1, (int) Math.round(Math.hypot(to[0] - from[0], to[1] - from[1])));
                for (int step = corner == 1 ? 0 : 1; step <= steps; step++) {
                    columns.add(new int[] {
                        from[0] + Math.round((float) (to[0] - from[0]) * step / steps),
                        from[1] + Math.round((float) (to[1] - from[1]) * step / steps)
                    });
                }
            }
            return columns;
        }

        private String place() {
            final StringBuilder place = new StringBuilder();
            for (final int[] corner : corners) {
                place.append(place.isEmpty() ? "" : " to ")
                        .append(corner[0])
                        .append(' ')
                        .append(corner[1]);
            }
            return place + ", y " + lowest + " to " + highest;
        }
    }

    /** Writes the picture; each world is a label and the folder of its overworld. */
    public void draw(final Path picture, final Line line, final List<String> labels, final List<Path> worlds)
            throws IOException {
        final List<int[]> columns = line.columns();
        final int scale = line.scale();
        final int height = (line.highest() - line.lowest() + 1) * scale;
        final BufferedImage image = new BufferedImage(
                Math.max(MINIMUM_WIDTH, columns.size() * scale),
                worlds.size() * (height + LABEL),
                BufferedImage.TYPE_INT_RGB);
        final Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        for (int world = 0; world < worlds.size(); world++) {
            final int top = world * (height + LABEL) + LABEL;
            graphics.setColor(Color.BLACK);
            graphics.drawString(labels.get(world) + ": " + line.place(), 6, top - 7);
            world(image, worlds.get(world), line, columns, top);
            graphics.setColor(new Color(0, 0, 0, 90));
            for (final int y : new int[] {0, 63}) {
                if (y > line.lowest() && y < line.highest()) {
                    final int at = top + (line.highest() - y) * scale;
                    graphics.drawLine(0, at, columns.size() * scale, at);
                }
            }
        }
        graphics.dispose();
        ImageIO.write(image, "png", picture.toFile());
    }

    private void world(
            final BufferedImage image, final Path world, final Line line, final List<int[]> columns, final int top)
            throws IOException {
        final Map<Long, ChunkBlocks> chunks = new HashMap<>();
        final int scale = line.scale();
        for (int column = 0; column < columns.size(); column++) {
            final int x = columns.get(column)[0];
            final int z = columns.get(column)[1];
            final long key = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
            if (!chunks.containsKey(key)) {
                chunks.put(key, chunk(world, x >> 4, z >> 4));
            }
            final ChunkBlocks blocks = chunks.get(key);
            for (int y = line.lowest(); blocks != null && y <= line.highest(); y++) {
                final int colour = colour(Section.name(blocks.entry(x & 15, y, z & 15)));
                for (int pixel = 0; pixel < scale * scale; pixel++) {
                    image.setRGB(
                            column * scale + pixel % scale, top + (line.highest() - y) * scale + pixel / scale, colour);
                }
            }
        }
    }

    private static @Nullable ChunkBlocks chunk(final Path world, final int chunkX, final int chunkZ)
            throws IOException {
        final int index = (chunkX & 31) + (chunkZ & 31) * Region.SIDE;
        final Map<String, Object> data = Region.read(
                        Region.file(world.resolve("region"), chunkX >> 5, chunkZ >> 5), wanted -> wanted == index)
                .get(index);
        return data == null || !ChunkBlocks.full(data) ? null : new ChunkBlocks(data);
    }

    private int colour(final String name) {
        return known.computeIfAbsent(name, key -> {
            final String plain = key.substring(key.indexOf(':') + 1);
            for (final String rule : COLOURS) {
                final String match = rule.substring(0, rule.indexOf(' '));
                if (match.startsWith("=") ? plain.equals(match.substring(1)) : plain.contains(match)) {
                    return Integer.parseInt(rule.substring(rule.indexOf(' ') + 1), 16);
                }
            }
            return UNKNOWN;
        });
    }
}
