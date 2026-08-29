package eu.nordtal.s2.common;

/**
 * Code points of the characters the resource pack defines, so a plugin never hardcodes a
 * private-use escape that the pack has since moved.
 *
 * <p>The authoritative mapping is
 * {@code resource-pack/src/assets/minecraft/font/default.json}. Anything added here must exist
 * there, and the resource pack's README table must be updated in the same change.
 */
public final class Glyphs {

    private Glyphs() {
    }

    // Role tags - U+E000..U+E00F
    public static final String TAG_SETTLER = "\uE000";
    public static final String TAG_CITIZEN = "\uE001";
    public static final String TAG_KNIGHT = "\uE002";
    public static final String TAG_LORD = "\uE003";
    public static final String TAG_ADMIN = "\uE004";

    // Region flags - U+E010..U+E01F
    public static final String FLAG_OTHER = "\uE010";
    public static final String FLAG_GERMANY = "\uE011";
    public static final String FLAG_NETHERLANDS = "\uE012";
    public static final String FLAG_UNITED_KINGDOM = "\uE013";
    public static final String FLAG_UNITED_STATES = "\uE014";

    // Logo assets - U+E020..U+E02F
    public static final String LOGO_HEIGHT_24 = "\uE020";
    public static final String LOGO_HEIGHT_32 = "\uE021";
}
