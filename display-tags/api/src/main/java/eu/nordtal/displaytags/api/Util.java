package eu.nordtal.displaytags.api;

import org.bukkit.Color;
import org.jspecify.annotations.Nullable;

/**
 * Shared parsing helpers used by both the plugin and its API.
 */
public class Util {
    /**
     * Parses a name tag background setting into the packed ARGB value the display entity expects.
     *
     * @param background {@code "default"}, {@code "transparent"}, a hex colour such as
     *                   {@code "#FFFFFF"}, or {@code null} (treated the same as {@code "default"})
     * @return the packed ARGB value
     */
    public static Integer parseDisplayBackground(final @Nullable String background) {
        if (background == null || background.equalsIgnoreCase("default")) return 1073741824;
        if (background.equalsIgnoreCase("transparent")) return 0;

        final String hex = background.startsWith("#") ? background.substring(1) : background;

        Color color = Color.fromARGB((int) Long.parseLong(hex, 16));
        if (hex.length() == 6) color = color.setAlpha(255);

        return color.asARGB();
    }
}
