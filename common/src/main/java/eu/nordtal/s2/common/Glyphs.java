package eu.nordtal.s2.common;

/**
 * Code points of the characters the resource pack defines, so a plugin never hardcodes a
 * private-use escape that the pack has since moved.
 *
 * <p><b>The authoritative mapping is {@code resource-pack/README.md}</b>, "Code point allocation"
 * (decided 2026-08-31). It covers both fonts - {@code minecraft/font/default.json} and
 * {@code nordtal/font/bossbar.json} - and this class, those two files and that table are three
 * mirrors of one allocation. A change is a change in all of them, in one commit.
 *
 * <p><b>This class is currently behind that table.</b> It still declares season 1's four role
 * tags, which season 2 deletes, and it declares nothing from {@code nordtal/font/bossbar.json} at
 * all - a whole font of bar segments, icons and space advances that no constant here names.
 * Bringing it in line is the resource-pack clean-up, not a change to make in passing.
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
