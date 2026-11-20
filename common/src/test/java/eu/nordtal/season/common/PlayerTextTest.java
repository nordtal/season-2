package eu.nordtal.season.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlayerTextTest {

    @Test
    void supplementaryPrivateUseAreasAreRemovedAndOtherTextStays() {
        final String input =
                points(0xEFFFF, 0xF0000, 0xF8000, 0xFFFFD, 0xFFFFE, 0xFFFFF, 0x100000, 0x108000, 0x10FFFD, 0x10FFFE)
                        + " Grüße 😀 𐍈";
        final String expected = points(0xEFFFF, 0xFFFFE, 0xFFFFF, 0x10FFFE) + " Grüße 😀 𐍈";

        assertEquals(expected, PlayerText.withoutPrivateUse(input));
    }

    private static String points(final int... codePoints) {
        final StringBuilder text = new StringBuilder();
        for (final int codePoint : codePoints) {
            text.appendCodePoint(codePoint);
        }
        return text.toString();
    }
}
