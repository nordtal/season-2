package eu.nordtal.season.packrendering.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LettersTest {

    @Test
    void lettersAdvanceAsVanillaDrawsThem() {
        assertEquals(6, Letters.advance('A'));
        assertEquals(2, Letters.advance('i'));
        assertEquals(4, Letters.advance(' '));
        assertEquals(6, Letters.advance('ü'));
        assertEquals(Symbol.ADVANCE, Letters.width(Symbol.STAR.glyph()));
    }

    @Test
    void aTextThatFitsItsRoomStaysWhole() {
        assertEquals("Lager", Letters.cut("Lager", Letters.visibleWidth("Lager")));
    }

    @Test
    void aTextWiderThanItsRoomEndsInAnEllipsisInsideIt() {
        final String cut = Letters.cut("Sehr langer Kistenname aus dem Amboss, der nicht passt", 161);

        assertTrue(cut.endsWith(Letters.ELLIPSIS), cut);
        assertTrue(Letters.visibleWidth(cut) <= 161, cut);
        assertTrue(Letters.visibleWidth(cut + "x") > 161 - Letters.width("x") - 2, cut);
    }

    @Test
    void aTextBreaksAtSpacesSoNoRowPassesTheWrap() {
        final List<String> rows =
                Letters.wrap("Fährt dich zum Bahnhof, den du als Favorit markiert hast und der weit weg ist", 100);

        assertTrue(rows.size() > 1);
        rows.forEach(row -> assertTrue(Letters.width(row) <= 100, row));
        assertEquals(
                "Fährt dich zum Bahnhof, den du als Favorit markiert hast und der weit weg ist",
                String.join(" ", rows));
    }
}
