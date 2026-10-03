package eu.nordtal.s2.messages.text;

import eu.nordtal.s2.messages.Tone;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The MiniMessage tags a text may use, and which of them an admin's override alone may. */
public final class Tags {

    /** The tone tags, one per {@link Tone}. */
    public static final Set<String> TONES =
            Arrays.stream(Tone.values()).map(Tone::tag).collect(Collectors.toUnmodifiableSet());

    private static final Set<String> DECORATIONS =
            Set.of("bold", "b", "italic", "i", "em", "underlined", "u", "strikethrough", "st", "obfuscated", "obf");

    /** Tags that stand alone and close nothing. */
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

    /** Colours by name, which a packaged text never uses: it names a tone. */
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

    /** Returns whether a packaged text may use the tag. */
    static boolean packaged(final String name) {
        final String bare = bare(name);
        return TONES.contains(bare) || DECORATIONS.contains(bare) || STRUCTURE.contains(bare);
    }

    /** Returns whether an admin's override may use the tag: everything a packaged text may, and colours. */
    static boolean override(final String name) {
        final String bare = bare(name);
        return packaged(name) || COLOURS.contains(bare) || bare.matches("#[0-9a-f]{6}");
    }

    /** Returns whether a colour or a gradient is what the tag sets. */
    static boolean colour(final String name) {
        final String bare = bare(name);
        return COLOURS.contains(bare) || bare.matches("#[0-9a-f]{6}");
    }

    /** Returns whether the tag stands alone, closing nothing. */
    static boolean isVoid(final String name) {
        return VOID.contains(bare(name));
    }

    /** Returns whether the tag is a line break. */
    public static boolean isLineBreak(final String name) {
        return "newline".equals(name) || "br".equals(name);
    }

    /** A negated decoration, {@code !italic}, is the decoration's. */
    private static String bare(final String name) {
        return name.startsWith("!") ? name.substring(1) : name;
    }

    /** Every tag name a packaged text may use, for an editor's list. */
    public static Set<String> packagedNames() {
        return Stream.of(TONES, DECORATIONS, STRUCTURE).flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
    }
}
