package eu.nordtal.season.underground.structures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The random spread of one structure set of the data pack, with the game's candidate chunk per cell.
 * In each cell of spacing by spacing chunks, the game and {@code /locate} look for a start only there.
 */
public record Spread(int spacing, int separation, int salt) {

    private static final Pattern NUMBER = Pattern.compile("\"(spacing|separation|salt)\"\\s*:\\s*(-?\\d+)");

    public Spread {
        if (spacing <= separation || separation < 0) {
            throw new IllegalArgumentException("A spread needs a spacing above its separation: " + spacing);
        }
    }

    /** Reads the placement of a structure set file of the data pack. */
    public static Spread read(final Path file) throws IOException {
        final String json = Files.readString(file, StandardCharsets.UTF_8);
        if (!json.contains("minecraft:random_spread")) {
            throw new IOException(file + " is not a random spread.");
        }
        int spacing = -1;
        int separation = -1;
        int salt = 0;
        final Matcher matcher = NUMBER.matcher(json);
        while (matcher.find()) {
            final int value = Integer.parseInt(matcher.group(2));
            switch (matcher.group(1)) {
                case "spacing" -> spacing = value;
                case "separation" -> separation = value;
                default -> salt = value;
            }
        }
        return new Spread(spacing, separation, salt);
    }

    /** Returns the candidate chunk of a cell as {x, z}, as the game's linear spread picks it. */
    public int[] candidate(final long seed, final int cellX, final int cellZ) {
        final Random random = new Random(cellX * 341_873_128_712L + cellZ * 132_897_987_541L + seed + salt);
        final int x = random.nextInt(spacing - separation);
        final int z = random.nextInt(spacing - separation);
        return new int[] {cellX * spacing + x, cellZ * spacing + z};
    }

    /** Returns the candidate chunk of the cell that holds a chunk. */
    public int[] candidateNear(final long seed, final int chunkX, final int chunkZ) {
        return candidate(seed, Math.floorDiv(chunkX, spacing), Math.floorDiv(chunkZ, spacing));
    }
}
