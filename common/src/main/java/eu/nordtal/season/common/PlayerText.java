package eu.nordtal.season.common;

/** Text a player writes for other players. */
public final class PlayerText {

    private PlayerText() {}

    /** Returns {@code text} without the code points of Supplementary Private Use Areas A and B. */
    public static String withoutPrivateUse(final String text) {
        return text.codePoints()
                .filter(codePoint ->
                        (codePoint < 0xF0000 || codePoint > 0xFFFFD) && (codePoint < 0x100000 || codePoint > 0x10FFFD))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
