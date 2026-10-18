package eu.nordtal.season.messages.text;

import eu.nordtal.season.messages.Tone;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The MiniMessage tags a text may use, and the tags only an admin's override may use. */
public final class Tags {

    /** The tone tags, one per {@link Tone}. */
    public static final Set<String> TONES =
            Arrays.stream(Tone.values()).map(Tone::tag).collect(Collectors.toUnmodifiableSet());

    private static final Set<String> DECORATIONS =
            Set.of("bold", "b", "italic", "i", "em", "underlined", "u", "strikethrough", "st", "obfuscated", "obf");

    private static final Set<String> VOID = Set.of("newline", "br", "reset", "glyph", "key", "lang", "tr", "translate");

    private static final Set<String> STRUCTURE = Set.of(
            "click",
            "hover",
            "insertion",
            "font",
            "action",
            "newline",
            "br",
            "reset",
            "glyph",
            "key",
            "lang",
            "tr",
            "translate");

    /** The colour names that a packaged text never uses. It names a tone instead. */
    private static final Set<String> COLOURS = Set.of(
            "black",
            "dark_blue",
            "dark_green",
            "dark_aqua",
            "dark_red",
            "dark_purple",
            "gold",
            "gray",
            "grey",
            "dark_gray",
            "dark_grey",
            "blue",
            "green",
            "aqua",
            "red",
            "light_purple",
            "yellow",
            "white",
            "color",
            "colour",
            "c",
            "gradient",
            "rainbow",
            "transition",
            "shadow",
            "pride");

    private Tags() {}

    static boolean packaged(final String name) {
        final String bare = bare(name);
        return TONES.contains(bare) || DECORATIONS.contains(bare) || STRUCTURE.contains(bare);
    }

    /** Returns whether an override may use the tag.
     * It may use every tag a packaged text may, and colours.
     */
    static boolean override(final String name) {
        final String bare = bare(name);
        return packaged(name) || COLOURS.contains(bare) || bare.matches("#[0-9a-f]{6}");
    }

    static boolean colour(final String name) {
        final String bare = bare(name);
        return COLOURS.contains(bare) || bare.matches("#[0-9a-f]{6}");
    }

    static boolean isVoid(final String name) {
        return VOID.contains(bare(name));
    }

    public static boolean isLineBreak(final String name) {
        return "newline".equals(name) || "br".equals(name);
    }

    /** Returns the decoration's name for a negated decoration such as {@code !italic}. */
    private static String bare(final String name) {
        return name.startsWith("!") ? name.substring(1) : name;
    }

    public static Set<String> packagedNames() {
        return Stream.of(TONES, DECORATIONS, STRUCTURE).flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
    }
}
