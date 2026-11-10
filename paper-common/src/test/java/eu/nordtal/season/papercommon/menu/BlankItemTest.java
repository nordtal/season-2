package eu.nordtal.season.papercommon.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.Letters;
import eu.nordtal.season.packrendering.ui.Tooltip;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Nordtal's tooltip on a display item: the server breaks its rows and colours them, the client only draws them. */
class BlankItemTest {

    @Test
    void aLongLoreLineIsBrokenIntoRowsWithinTheWrap() {
        final String text = "Importiert ein Item in Steward, und noch viel mehr Worte, die eine Zeile sprengen.";
        final List<Component> rows =
                BlankItem.rows(Component.text("Radgewinn hineinlegen"), List.of(Component.text(text)));

        assertTrue(rows.size() > 2);
        rows.forEach(row -> assertTrue(Letters.width(plain(row)) <= Tooltip.WRAP, plain(row)));
        assertEquals(
                text,
                String.join(
                        " ",
                        rows.subList(1, rows.size()).stream()
                                .map(BlankItemTest::plain)
                                .toList()));
    }

    @Test
    void theNameIsInTheTitleColourAndTheLoreInTooltipTextWithoutAShadow() {
        final List<Component> rows = BlankItem.rows(
                Component.text("Diamant", NamedTextColor.AQUA),
                List.of(Component.text("In Steward importiert", NamedTextColor.DARK_PURPLE)));

        assertEquals(Colours.TOOLTIP_TITLE, rows.get(0).color());
        assertEquals(Colours.TOOLTIP_TEXT, rows.get(1).color());
        rows.forEach(row -> assertEquals(ShadowColor.none(), row.shadowColor()));
    }

    @Test
    void aLineTheClientTranslatesStaysWholeAndTakesTheRowColour() {
        final Component name = Component.translatable("item.minecraft.iron_ingot", NamedTextColor.GRAY);
        final List<Component> rows = BlankItem.rows(Component.text("Gesucht"), List.of(name));

        assertEquals(2, rows.size());
        assertEquals(Colours.TOOLTIP_TEXT, rows.get(1).color());
    }

    private static String plain(final Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
