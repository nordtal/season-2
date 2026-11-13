package eu.nordtal.season.underground.block;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Sorts block names into the kinds the transplant tells apart, and packs a kind and a filling rock into a byte. */
public final class Blocks {

    /** Natural ground: the terrain list. */
    public static final int ROCK = 0;

    /** Any kind of air. */
    public static final int AIR = 1;

    /** Water. */
    public static final int WATER = 2;

    /** Lava. */
    public static final int LAVA = 3;

    /** Plants and the like in the map, and in the generated world everything in a cave that is not ground. */
    public static final int LOOSE = 4;

    /** A block somebody placed: every block of the map that is on no list. */
    public static final int BUILT = 5;

    /** Ground or building, decided by whether a built block is near; it carries its filling rock like ground. */
    public static final int AMBIGUOUS = 6;

    /** The bits of a cell that hold its kind; the bits above hold the filling rock. */
    public static final int KIND = 7;

    private static final List<String> FILLING = List.of(
            "",
            "stone",
            "deepslate",
            "granite",
            "diorite",
            "andesite",
            "tuff",
            "calcite",
            "dirt",
            "coarse_dirt",
            "sandstone",
            "red_sandstone",
            "terracotta",
            "smooth_basalt",
            "basalt",
            "blackstone",
            "packed_ice",
            "mud",
            "clay",
            "dripstone_block",
            "moss_block",
            "podzol",
            "rooted_dirt",
            "snow_block");
    private static final List<String> LOOSE_ENDINGS = List.of(
            "_leaves",
            "_log",
            "_wood",
            "_sapling",
            "_carpet",
            "_mushroom",
            "_flower",
            "_tulip",
            "_bush",
            "vine",
            "vines",
            "_plant",
            "coral",
            "_fan",
            "_roots",
            "_stem",
            "_mushroom_block",
            "_petals",
            "_orchid",
            "_daisy");

    private final Set<String> terrain;
    private final Set<String> ambiguous;
    private final Set<String> loose;
    private final Map<String, Byte> mapCells = new HashMap<>();
    private final Map<String, Byte> parallelCells = new HashMap<>();

    private Blocks(final Set<String> terrain, final Set<String> ambiguous, final Set<String> loose) {
        this.terrain = terrain;
        this.ambiguous = ambiguous;
        this.loose = loose;
    }

    /** Reads the lists that ship with the tool. */
    public static Blocks load() {
        return new Blocks(list("terrain.txt"), list("ambiguous.txt"), list("loose.txt"));
    }

    /** Returns whether a cell is open: air, a fluid or something loose. */
    public static boolean open(final int cell) {
        final int kind = cell & KIND;
        return kind >= AIR && kind <= LOOSE;
    }

    /** Returns the cell for a block of the map. */
    public byte mapCell(final String name) {
        return mapCells.computeIfAbsent(name, key -> cell(key, true));
    }

    /** Returns the cell for a block of the generated world, where nothing counts as built. */
    public byte parallelCell(final String name) {
        return parallelCells.computeIfAbsent(name, key -> cell(key, false));
    }

    /** Returns the block that fills a cell with this filling rock, or the rock of that depth when it has none. */
    public static String filling(final int cell, final int y) {
        final int code = (cell & 0xFF) >> 3;
        if (code == 0 || code >= FILLING.size()) {
            return y < 0 ? "minecraft:deepslate" : "minecraft:stone";
        }
        return "minecraft:" + FILLING.get(code);
    }

    /** Returns whether a cell names a rock to fill with. */
    public static boolean fills(final int cell) {
        return (cell & KIND) == ROCK && (cell & 0xFF) >> 3 != 0;
    }

    private byte cell(final String name, final boolean map) {
        final String plain = name.substring(name.indexOf(':') + 1);
        final int kind;
        if (plain.equals("air") || plain.equals("cave_air") || plain.equals("void_air")) {
            kind = AIR;
        } else if (plain.equals("water") || plain.equals("bubble_column")) {
            kind = WATER;
        } else if (plain.equals("lava")) {
            kind = LAVA;
        } else if (map && ambiguous.contains(plain)) {
            kind = AMBIGUOUS;
        } else if (terrain.contains(plain) || ambiguous.contains(plain)) {
            kind = ROCK;
        } else {
            kind = !map || loose(plain) ? LOOSE : BUILT;
        }
        final int code = kind == ROCK || kind == AMBIGUOUS ? Math.max(0, FILLING.indexOf(plain)) : 0;
        return (byte) (kind | code << 3);
    }

    private boolean loose(final String plain) {
        if (loose.contains(plain)) {
            return true;
        }
        if (plain.endsWith("grass")) {
            return true;
        }
        return LOOSE_ENDINGS.stream().anyMatch(plain::endsWith);
    }

    private static Set<String> list(final String resource) {
        final Set<String> names = new HashSet<>();
        try (InputStream in = Blocks.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("The list " + resource + " is missing from the tool.");
            }
            final BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.isBlank()) {
                    names.add(line.strip());
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }
}
