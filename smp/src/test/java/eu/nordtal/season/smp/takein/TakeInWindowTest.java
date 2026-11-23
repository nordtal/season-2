package eu.nordtal.season.smp.takein;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.packrendering.Glyphs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

/** Verifies the take-in window's bold chest header, quiet rule and full-row plate. */
class TakeInWindowTest {

    @Test
    void theTitleUsesTheChestSymbolInBothTheBoldHeaderAndThePlate() {
        final Component title = TakeInWindow.title("Put in a wheel prize");

        assertTrue(hasBoldText(title, "Put in a wheel prize"));
        assertTrue(hasRule(title));
        assertTrue(countGlyph(title, Glyphs.UI_SYMBOL_CHEST) >= 2);
    }

    private static boolean hasBoldText(final Component component, final String expected) {
        if (component instanceof final TextComponent text
                && text.hasDecoration(TextDecoration.BOLD)
                && text.content().contains(expected)) {
            return true;
        }
        return component.children().stream().anyMatch(child -> hasBoldText(child, expected));
    }

    private static boolean hasRule(final Component component) {
        if (component instanceof final TextComponent text
                && Glyphs.UI_RULE.stream().anyMatch(glyph -> text.content().contains(glyph))) {
            return true;
        }
        return component.children().stream().anyMatch(TakeInWindowTest::hasRule);
    }

    private static int countGlyph(final Component component, final String glyph) {
        int count = 0;
        if (component instanceof final TextComponent text) {
            int index = 0;
            while ((index = text.content().indexOf(glyph, index)) >= 0) {
                count++;
                index += glyph.length();
            }
        }
        for (final Component child : component.children()) {
            count += countGlyph(child, glyph);
        }
        return count;
    }
}
