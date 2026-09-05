package eu.nordtal.s2.common.hud;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * How far every {@code nordtal:bossbar} glyph moves the cursor, so a HUD pill can be drawn as wide
 * as what it holds.
 *
 * <h2>Why a server can know this at all</h2>
 * The boss bar font is ours: its ascii sheet, its icons and its arrows are PNGs in this repository,
 * and the client derives every advance from those pixels by one rule - a {@code space} provider's
 * number, or a bitmap's rightmost drawn column plus two. That is the same rule {@code BoardFrame}
 * cannot use for the boards, whose text renders in {@code minecraft:default} out of the client
 * jar. Here it holds, so the width of "Nordtal" in the HUD is a fact about a file we ship.
 *
 * <h2>Where the table comes from</h2>
 * {@code resource-pack/tools/export_bossbar_advances.py} writes it to
 * {@code nordtal/hud/bossbar-advances.properties} in this module's resources, one line per code
 * point; {@code BossBarAdvancesTest} derives the same table from the pack with the same rule and
 * fails {@code check} if the two disagree. A redrawn icon whose rightmost column moved is therefore
 * caught in the build, not on a player's screen as a pill one pixel too narrow.
 *
 * <h2>What an unknown character costs</h2>
 * A code point the font does not declare is drawn by the client as the missing-glyph box, which is
 * five pixels wide and advances six. {@link #MISSING} is that six, so a pill around such a
 * character is still the right width - the box itself is the visible failure, and
 * {@code ResourcePackTest} is what keeps it out of the bundles.
 */
public final class BossBarAdvances {

    /** The advance of the client's missing-glyph box: five pixels plus the separator. */
    public static final int MISSING = 6;

    private static final String RESOURCE = "/nordtal/hud/bossbar-advances.properties";

    private static final Map<Integer, Integer> TABLE = load();

    private BossBarAdvances() {
    }

    /** @return how far {@code codePoint} moves the cursor, or {@link #MISSING} if the font lacks it */
    public static int advance(final int codePoint) {
        return TABLE.getOrDefault(codePoint, MISSING);
    }

    /** @return true when the font declares {@code codePoint} */
    public static boolean covers(final int codePoint) {
        return TABLE.containsKey(codePoint);
    }

    /** @return the cursor's displacement after drawing {@code text} in {@code nordtal:bossbar} */
    public static int width(final String text) {
        return text.codePoints().map(BossBarAdvances::advance).sum();
    }

    /** @return the whole table, code point to advance - for tests, and for nothing else */
    public static Map<Integer, Integer> table() {
        return Map.copyOf(TABLE);
    }

    private static Map<Integer, Integer> load() {
        final Properties properties = new Properties();
        try (InputStream stream = BossBarAdvances.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the classpath - run"
                        + " resource-pack/tools/export_bossbar_advances.py");
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + RESOURCE, e);
        }
        final Map<Integer, Integer> table = new HashMap<>();
        properties.forEach((key, value) ->
                table.put(Integer.parseInt(String.valueOf(key), 16),
                        Integer.parseInt(String.valueOf(value).trim())));
        return table;
    }
}
