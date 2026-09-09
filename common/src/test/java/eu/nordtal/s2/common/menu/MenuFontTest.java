package eu.nordtal.s2.common.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.RepositoryRoot;
import eu.nordtal.s2.common.pack.FontFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds {@link MenuFont}'s advance table against the six row fonts it was exported from.
 *
 * <h2>Why this is the check that matters</h2>
 * The server composes a menu row: it decides where a pill starts, where its label starts, and where
 * a right-aligned distance ends. Every one of those is arithmetic on the width of the glyphs the
 * <em>client</em> will draw, so a table one pixel out is a row one pixel out - and one pixel out is
 * not a failure anywhere. Nothing throws, nothing logs, the menu opens, and the number at the right
 * edge sits a hair inside or outside its pill for the rest of the season.
 *
 * <p>The way that happens is somebody redrawing a glyph and not re-running
 * {@code resource-pack/tools/generate_gui_rows.py}. So this derives the whole table from the pack
 * again, by the client's own rule - a {@code space} provider's number, or the rightmost column of
 * the glyph's cell that carries any alpha, plus one for that column and one the client adds after
 * every glyph - and fails if the shipped resource disagrees. It is the same arrangement, and the
 * same reason, as {@code BossBarAdvancesTest}.</p>
 *
 * <h2>And the ascents, which nothing else can see</h2>
 * A row font's whole purpose is its three ascents. Getting one wrong draws the right picture on the
 * wrong row, or half a row off, which reads as a layout bug rather than as a font mistake - and no
 * test that only looks at widths would notice. So the second half of this class asserts each of the
 * eighteen ascents puts its glyph's top exactly where {@link SlotGeometry} says that row's slot
 * cell is, plus the inset the furniture is drawn at.
 */
class MenuFontTest {

    private static final String ASSETS = "resource-pack/src/assets";

    /** The pill's inset inside its slot cell - the same two pixels a balloon card is inset by. */
    private static final int INSET = 2;

    private static final int FURNITURE_HEIGHT = SlotGeometry.PITCH - 2 * INSET;

    /** The title's baseline: a glyph's top lands at this minus its ascent. */
    private static final int BASELINE = 13;

    @Test
    @DisplayName("the exported advance table is what the client would derive from the pack")
    void theTableIsThePack() {
        assertEquals(new TreeMap<>(derive()), new TreeMap<>(MenuFont.table()),
                "common/src/main/resources/nordtal/menu/gui-row-advances.properties disagrees with"
                        + " the row fonts it was exported from. Re-run"
                        + " resource-pack/tools/generate_gui_rows.py: a redrawn glyph whose"
                        + " rightmost column moved is a row laid out on the old width, and nothing"
                        + " about that fails anywhere but on a client");
    }

    @Test
    @DisplayName("every row glyph the code names has an advance, so it can be placed at all")
    void everyRowGlyphIsMeasurable() {
        for (final String glyph : new String[] {
                Glyphs.GUI_ROW_PILL, Glyphs.GUI_ROW_FRAME, Glyphs.GUI_ROW_BUTTON_WIDE,
                Glyphs.GUI_ROW_BUTTON_SMALL, Glyphs.GUI_ROW_BUTTON_SMALL_OFF,
                Glyphs.GUI_ROW_ICON_SPAWN, Glyphs.GUI_ROW_ICON_DEATH, Glyphs.GUI_ROW_ICON_POI,
                Glyphs.GUI_ROW_ICON_STOP, Glyphs.GUI_ROW_ICON_PREV, Glyphs.GUI_ROW_ICON_NEXT}) {
            assertTrue(MenuFont.advance(glyph.codePointAt(0)) > 0,
                    "U+%X has no advance, so anything drawn after it lands on top of it"
                            .formatted(glyph.codePointAt(0)));
        }
    }

    @Test
    @DisplayName("text is folded onto the sheet: capitals, ß kept, anything else a question mark")
    void theFoldIsTheAlphabet() {
        assertEquals("BAECKEREI", MenuFont.fold("Baeckerei"));
        assertEquals("STRASSE", MenuFont.fold("Strasse"));
        // The one character with no single-character upper case. String#toUpperCase turns it into
        // SS, which would be two glyphs where the sheet has one - and the sheet has one.
        assertEquals("STRAßE", MenuFont.fold("Straße"));
        assertEquals("MÜHLE", MenuFont.fold("mühle"));
        // A POI name is typed by a player, so this is the ordinary case and not the exotic one.
        assertEquals("A?B", MenuFont.fold("aéb"));
        assertEquals("??", MenuFont.fold("<>"));
    }

    @Test
    @DisplayName("a folded string measures what it will draw, and an unknown character costs a ?")
    void theWidthIsTheDrawnWidth() {
        assertEquals(MenuFont.width(MenuFont.fold("?")), MenuFont.width(MenuFont.fold("é")));
        assertEquals(0, MenuFont.width(""));
        assertEquals(MenuFont.advance(' '), MenuFont.width(" "));
    }

    @Test
    @DisplayName("fit shortens until it fits, and leaves alone what already does")
    void fitStaysInsideItsPill() {
        final String short_ = "MINE";
        assertEquals(short_, MenuFont.fit(short_, 120));
        for (final int pixels : new int[] {8, 12, 20, 40, 120}) {
            final String fitted = MenuFont.fit("Baeckerei am Fluss", pixels);
            assertTrue(MenuFont.width(fitted) <= pixels,
                    "fit(..., " + pixels + ") came back " + MenuFont.width(fitted) + " wide as '"
                            + fitted + "' - a label wider than its pill runs over the number"
                            + " right-aligned beside it, and neither is readable then");
        }
    }

    @Test
    @DisplayName("a row font is refused for a row a chest does not have")
    void thereIsNoSeventhRow() {
        assertThrows(IllegalArgumentException.class,
                () -> MenuTitle.onPlain(6).rowText("X", 6, 9, null));
        assertThrows(IllegalArgumentException.class,
                () -> MenuTitle.onPlain(6).rowArt(Glyphs.GUI_ROW_PILL, -1, 9, null));
    }

    @Test
    @DisplayName("each row font's three ascents put its glyphs on that row's slot cell")
    void theAscentsAreTheRows() {
        for (int row = 0; row < MenuTitle.MAX_ROWS; row++) {
            final Map<Integer, JsonObject> providers = bitmaps("gui_r" + row + ".json");
            final int cell = SlotGeometry.y(row);

            assertEquals(cell + INSET, top(providers.get(Glyphs.GUI_ROW_PILL.codePointAt(0))),
                    "row " + row + "'s pill is not drawn on row " + row + "'s slot cell");
            assertEquals(cell + INSET + (FURNITURE_HEIGHT - 8) / 2,
                    top(providers.get(Glyphs.GUI_ROW_ICON_POI.codePointAt(0))),
                    "row " + row + "'s icons are not centred in its pill");
            assertEquals(cell + INSET + (FURNITURE_HEIGHT - MenuFont.HEIGHT) / 2,
                    top(providers.get((int) 'A')),
                    "row " + row + "'s text is not centred in its pill - five rows centred in"
                            + " fourteen lands at +4 and not +3, and one pixel high on every line"
                            + " of every list menu is the kind of wrong that gets lived with");
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private static int top(final JsonObject provider) {
        assertTrue(provider != null, "the row font does not declare that glyph at all");
        return BASELINE - provider.get("ascent").getAsInt();
    }

    /** Code point to the bitmap provider that declares it, for one row font. */
    private static Map<Integer, JsonObject> bitmaps(final String file) {
        final Map<Integer, JsonObject> out = new LinkedHashMap<>();
        final JsonObject root = JsonParser
                .parseString(RepositoryRoot.read(ASSETS + "/nordtal/font/" + file))
                .getAsJsonObject();
        for (final JsonElement element : root.getAsJsonArray("providers")) {
            final JsonObject provider = element.getAsJsonObject();
            if (!"bitmap".equals(provider.get("type").getAsString())) {
                continue;
            }
            provider.getAsJsonArray("chars").forEach(chars ->
                    chars.getAsString().codePoints().forEach(codePoint ->
                            out.putIfAbsent(codePoint, provider)));
        }
        return out;
    }

    /**
     * The whole table again, from {@code gui_r0.json} and its PNGs, by the client's own rule.
     *
     * <p>{@code gui_r0} alone is enough because {@code ResourcePackTest} asserts the six declare
     * the same characters, and an advance does not depend on the ascent.</p>
     */
    private static Map<Integer, Integer> derive() {
        final Map<Integer, Integer> table = new TreeMap<>();
        final JsonObject root = JsonParser
                .parseString(RepositoryRoot.read(ASSETS + "/nordtal/font/gui_r0.json"))
                .getAsJsonObject();
        for (final JsonElement element : root.getAsJsonArray("providers")) {
            final JsonObject provider = element.getAsJsonObject();
            if ("space".equals(provider.get("type").getAsString())) {
                provider.getAsJsonObject("advances").entrySet().forEach(entry ->
                        entry.getKey().codePoints().forEach(codePoint ->
                                table.putIfAbsent(codePoint, entry.getValue().getAsInt())));
                continue;
            }
            final BufferedImage image = read(provider.get("file").getAsString());
            final var rows = provider.getAsJsonArray("chars");
            final int columns = rows.get(0).getAsString().codePointCount(0,
                    rows.get(0).getAsString().length());
            final int cellWidth = image.getWidth() / columns;
            final int cellHeight = image.getHeight() / rows.size();
            final double scale = provider.get("height").getAsDouble() / cellHeight;
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                final int[] codePoints = rows.get(rowIndex).getAsString().codePoints().toArray();
                for (int column = 0; column < codePoints.length; column++) {
                    if (codePoints[column] == 0 || table.containsKey(codePoints[column])) {
                        continue;
                    }
                    final int rightmost = rightmostDrawnColumn(image, column * cellWidth,
                            rowIndex * cellHeight, cellWidth, cellHeight);
                    table.put(codePoints[column], (int) (0.5 + (rightmost + 1) * scale) + 1);
                }
            }
        }
        assertEquals(new TreeSet<>(table.keySet()), new TreeSet<>(MenuFont.table().keySet()),
                "the exported table and the row font declare different code points");
        return table;
    }

    private static int rightmostDrawnColumn(final BufferedImage image, final int x0, final int y0,
                                            final int width, final int height) {
        for (int x = x0 + width - 1; x >= x0; x--) {
            for (int y = y0; y < y0 + height; y++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    return x - x0;
                }
            }
        }
        return -1;
    }

    private static BufferedImage read(final String textureId) {
        try {
            final BufferedImage image = ImageIO.read(FontFile.texturePath(textureId).toFile());
            if (image == null) {
                throw new IllegalStateException(textureId + " is not an image ImageIO can read");
            }
            return image;
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + textureId, e);
        }
    }
}
