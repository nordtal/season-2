package eu.nordtal.s2.hungergames.color;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates a palette of {@code n} evenly spaced hues at fixed saturation/lightness, and maps any
 * RGB colour to its nearest named Minecraft colour - see
 * {@code docs/hunger-games.md#teams-colours-and-hearts}: "evenly spaced hues at a fixed saturation
 * and lightness, which is the arrangement that separates n colours best."
 * <p>
 * No Bukkit or Adventure type appears here on purpose: {@code java.awt.Color} is the JDK's own RGB
 * type and is enough to generate and reason about a palette in a plain unit test. The plugin's
 * game code converts the {@code int} RGB this class produces into
 * {@code net.kyori.adventure.text.format.TextColor} at the point it is actually rendered.
 * </p>
 */
public final class TeamColours {

    /** Fixed per docs/hunger-games.md - "a fixed saturation and lightness". */
    public static final float SATURATION = 0.85f;

    /** Fixed per docs/hunger-games.md - "a fixed saturation and lightness". */
    public static final float BRIGHTNESS = 0.95f;

    /**
     * The sixteen named Minecraft colours, as their {@code net.kyori.adventure.text.format.NamedTextColor}
     * RGB values (which match {@code org.bukkit.ChatColor}'s colours exactly - both describe the
     * same sixteen chat colours). Hardcoded rather than resolved reflectively: these sixteen values
     * are part of the Minecraft protocol and do not change between versions, and hardcoding keeps
     * this class free of any platform dependency, adventure included.
     */
    private static final Map<String, Integer> NAMED_COLOURS = Map.ofEntries(
            Map.entry("BLACK", 0x000000),
            Map.entry("DARK_BLUE", 0x0000AA),
            Map.entry("DARK_GREEN", 0x00AA00),
            Map.entry("DARK_AQUA", 0x00AAAA),
            Map.entry("DARK_RED", 0xAA0000),
            Map.entry("DARK_PURPLE", 0xAA00AA),
            Map.entry("GOLD", 0xFFAA00),
            Map.entry("GRAY", 0xAAAAAA),
            Map.entry("DARK_GRAY", 0x555555),
            Map.entry("BLUE", 0x5555FF),
            Map.entry("GREEN", 0x55FF55),
            Map.entry("AQUA", 0x55FFFF),
            Map.entry("RED", 0xFF5555),
            Map.entry("LIGHT_PURPLE", 0xFF55FF),
            Map.entry("YELLOW", 0xFFFF55),
            Map.entry("WHITE", 0xFFFFFF));

    private TeamColours() {
    }

    /**
     * Generates {@code count} evenly spaced hues, in order, at {@link #SATURATION} and
     * {@link #BRIGHTNESS}.
     *
     * @param count how many teams need a colour; must be positive
     * @return {@code count} RGB colours as packed {@code 0xRRGGBB} ints, starting at hue 0
     * @throws IllegalArgumentException if {@code count} is not positive
     */
    public static List<Integer> generatePalette(final int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive, was " + count);
        }

        final List<Integer> palette = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            final float hue = (float) index / count;
            final int rgb = Color.HSBtoRGB(hue, SATURATION, BRIGHTNESS) & 0xFFFFFF;
            palette.add(rgb);
        }
        return palette;
    }

    /**
     * The nearest of the sixteen named Minecraft colours to an exact RGB colour, by Euclidean
     * distance in RGB space - what {@code docs/hunger-games.md#teams-colours-and-hearts} calls the
     * "nearest named colour" for the vanilla surfaces (scoreboard team, tab list) that cannot take
     * an exact one.
     *
     * @param rgb a packed {@code 0xRRGGBB} colour
     * @return the matching {@code NamedTextColor}/{@code ChatColor} constant name, e.g.
     *         {@code "DARK_AQUA"} - matches what this plugin writes to {@code hg_team.colour_named}
     */
    public static String nearestNamedColour(final int rgb) {
        final int r = (rgb >> 16) & 0xFF;
        final int g = (rgb >> 8) & 0xFF;
        final int b = rgb & 0xFF;

        String best = null;
        long bestDistance = Long.MAX_VALUE;
        for (final Map.Entry<String, Integer> entry : NAMED_COLOURS.entrySet()) {
            final int candidate = entry.getValue();
            final int cr = (candidate >> 16) & 0xFF;
            final int cg = (candidate >> 8) & 0xFF;
            final int cb = candidate & 0xFF;

            final long dr = r - cr;
            final long dg = g - cg;
            final long db = b - cb;
            final long distance = dr * dr + dg * dg + db * db;

            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry.getKey();
            }
        }
        return best;
    }
}
