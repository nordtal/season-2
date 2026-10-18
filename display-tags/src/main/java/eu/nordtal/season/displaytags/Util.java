package eu.nordtal.season.displaytags;

import org.bukkit.Color;
import org.jspecify.annotations.Nullable;

/** Parsing helpers for the plugin and its API. */
public class Util {
    /**
     * Parses a background setting into the packed ARGB value of the display entity.
     * The setting is {@code "default"} (also for null), {@code "transparent"}
     * or a hex colour such as {@code "#FFFFFF"}.
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
