package eu.nordtal.season.underground;

import eu.nordtal.season.underground.anvil.Region;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The options of one command, each written as a name with two dashes and its value. */
public final class Arguments {

    private final Map<String, String> values = new HashMap<>();

    public Arguments(final List<String> words) {
        if (words.size() % 2 != 0) {
            throw new IllegalArgumentException("Every option is a name and a value: " + words);
        }
        for (int i = 0; i < words.size(); i += 2) {
            if (!words.get(i).startsWith("--")) {
                throw new IllegalArgumentException("An option starts with two dashes: " + words.get(i));
            }
            values.put(words.get(i).substring(2), words.get(i + 1));
        }
    }

    /** Returns the value of an option that the command cannot run without. */
    public String text(final String name) {
        final String value = values.get(name);
        if (value == null) {
            throw new IllegalArgumentException("The option --" + name + " is missing.");
        }
        return value;
    }

    /** Returns the value of an option, or the fallback when the option is absent. */
    public String text(final String name, final String fallback) {
        return values.getOrDefault(name, fallback);
    }

    /** Returns the value of an option as a path. */
    public Path path(final String name) {
        return Path.of(text(name));
    }

    /** Returns the value of an option as a number, or the fallback when the option is absent. */
    public double number(final String name, final double fallback) {
        final String value = values.get(name);
        try {
            return value == null ? fallback : Double.parseDouble(value);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " " + value + " is not a number. Give a number.", e);
        }
    }

    /** Returns the value of an option that the command cannot run without, as a whole number. */
    public long whole(final String name) {
        final String value = text(name);
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(
                    "--" + name + " " + value + " is not a whole number. Give a whole number.", e);
        }
    }

    /** Returns whether an option is given. */
    public boolean has(final String name) {
        return values.containsKey(name);
    }

    /** Returns the regions to work on: the rectangle of --regions x1,z1,x2,z2, or every region file of a world. */
    public List<int[]> regions(final Path world) throws IOException {
        final List<int[]> regions = new ArrayList<>();
        if (has("regions")) {
            final String[] corners = text("regions").split(",", -1);
            for (int x = Integer.parseInt(corners[0]); x <= Integer.parseInt(corners[2]); x++) {
                for (int z = Integer.parseInt(corners[1]); z <= Integer.parseInt(corners[3]); z++) {
                    regions.add(new int[] {x, z});
                }
            }
            return regions;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(world.resolve("region"), "r.*.mca")) {
            for (final Path file : files) {
                final int[] coordinates = Region.coordinates(file);
                if (coordinates != null) {
                    regions.add(coordinates);
                }
            }
        }
        regions.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        return regions;
    }
}
