package eu.nordtal.s2.common.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.RepositoryRoot;
import eu.nordtal.s2.common.pack.FontFile;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The menu panel's offset arithmetic, measured against the real {@code nordtal:gui} font.
 *
 * <h2>Why this is worth a test</h2>
 * Nothing about a wrong offset fails, logs, or throws. The menu opens, the panel is drawn, and it
 * is a few pixels out - which looks like art that does not quite fit rather than like arithmetic
 * that is wrong, so it gets attributed to the placeholder and survives. The one number people get
 * wrong is the advance: a 176px glyph moves the cursor 177, because every bitmap glyph carries a
 * trailing pixel. Assume 176 and every menu on the server is one pixel out in the same direction.
 *
 * <p>So the assertions below do not restate {@code MenuTitle}'s constants back at it. They read
 * {@code gui.json} and the panel PNGs and derive the advance from the pack, which is the only way
 * this test can disagree with the code.</p>
 */
class MenuTitleTest {

    private static final String FONT = "resource-pack/src/assets/nordtal/font/gui.json";
    private static final String ASSETS = "resource-pack/src/assets";

    /** Code point to advance, for the {@code space} provider. */
    private static final Map<Integer, Integer> ADVANCES = new LinkedHashMap<>();

    /** Code point to the advance a panel glyph carries, derived from its PNG and its provider. */
    private static final Map<Integer, Integer> PANELS = new LinkedHashMap<>();

    /** Code point to the advance an overlay glyph carries - anything narrower than a window. */
    private static final Map<Integer, Integer> OVERLAYS = new LinkedHashMap<>();

    /** Code point to the {@code ascent} its provider declares. */
    private static final Map<Integer, Integer> ASCENTS = new HashMap<>();

    static {
        final JsonObject root = JsonParser.parseString(RepositoryRoot.read(FONT)).getAsJsonObject();
        for (final JsonElement element : root.getAsJsonArray("providers")) {
            final JsonObject provider = element.getAsJsonObject();
            if ("space".equals(provider.get("type").getAsString())) {
                provider.getAsJsonObject("advances").entrySet().forEach(entry ->
                        entry.getKey().codePoints().forEach(codePoint ->
                                ADVANCES.put(codePoint, entry.getValue().getAsInt())));
                continue;
            }
            final String file = provider.get("file").getAsString();
            final BufferedImage image = read(file);
            final int declaredHeight = provider.get("height").getAsInt();
            // A bitmap glyph is scaled so its rendered height is `height`, and its advance is the
            // scaled width plus one. Declaring the PNG's own height is what keeps that 1:1 - and a
            // panel that is not 1:1 is a blurry panel, so the equality is the check, not a detail.
            assertEquals(image.getHeight(), declaredHeight,
                    file + " has to declare its own pixel height, or the panel is scaled");
            final int codePoint = provider.getAsJsonArray("chars").get(0).getAsString().codePointAt(0);
            ASCENTS.put(codePoint, provider.get("ascent").getAsInt());
            if (image.getWidth() == MenuTitle.PANEL_ADVANCE - 1) {
                PANELS.put(codePoint, image.getWidth() + 1);
            } else {
                OVERLAYS.put(codePoint, image.getWidth() + 1);
            }
        }
    }

    @Test
    @DisplayName("the composed title ends exactly where an uncomposed one would start")
    void theNetDisplacementIsZero() {
        for (int rows = 1; rows <= MenuTitle.MAX_ROWS; rows++) {
            assertEquals(0, displacement(plain(MenuTitle.panel(rows))),
                    "the panel for " + rows + " rows does not return the cursor to the title"
                            + " anchor, so the readable title is drawn off its usual spot - and"
                            + " nothing about that fails, it just looks slightly wrong forever");
        }
    }

    @Test
    @DisplayName("a 176px panel advances 177, and the walk back is composed from that")
    void theAdvanceCarriesTheTrailingPixel() {
        for (final Map.Entry<Integer, Integer> panel : PANELS.entrySet()) {
            assertEquals(MenuTitle.PANEL_ADVANCE, panel.getValue(),
                    "U+%X advances %d in the pack but MenuTitle assumes %d"
                            .formatted(panel.getKey(), panel.getValue(), MenuTitle.PANEL_ADVANCE));
        }
        assertEquals(
                Glyphs.GUI_SPACE_MINUS_128 + Glyphs.GUI_SPACE_MINUS_32
                        + Glyphs.GUI_SPACE_MINUS_8 + Glyphs.GUI_SPACE_MINUS_1,
                MenuTitle.shift(MenuTitle.PANEL_ADVANCE - MenuTitle.ANCHOR_X),
                "169 = 128 + 32 + 8 + 1, largest first - the advances are powers of two, so there"
                        + " is exactly one way to write it");
    }

    @Test
    @DisplayName("each chest size draws its own panel and no other")
    void everyRowCountPicksItsOwnPanel() {
        for (int rows = 1; rows <= MenuTitle.MAX_ROWS; rows++) {
            final String composed = plain(MenuTitle.panel(rows));
            final int expected = Glyphs.GUI_PANELS[rows - 1].codePointAt(0);
            final List<Integer> drawn = composed.codePoints().filter(PANELS::containsKey).boxed().toList();
            assertEquals(List.of(expected), drawn,
                    "a panel drawn for the wrong row count is the wrong height, and a chest window"
                            + " is 114 + 18*rows - six rows over one row is 90 pixels of overhang");
        }
    }

    @Test
    @DisplayName("every panel rises on the ascent the title anchor needs")
    void everyPanelDeclaresTheSameAscent() {
        for (final int codePoint : PANELS.keySet()) {
            assertEquals(13, ASCENTS.get(codePoint),
                    "the title's top is at y = 6 and the default font's ascent is 7, so a glyph's"
                            + " top lands at y + 7 - ascent. 13 puts it at 0, the window's own top"
                            + " edge; anything else offsets the whole panel vertically");
        }
        assertTrue(PANELS.size() >= MenuTitle.MAX_ROWS + 1,
                "six chest panels and the travel panel were expected; found " + PANELS.size());
    }

    @Test
    @DisplayName("every overlay lands on a slot row: its ascent is 13 minus a y inside the window")
    void everyOverlayLandsInsideTheWindow() {
        assertTrue(!OVERLAYS.isEmpty(), "the travel overlays were expected in gui.json");
        for (final int codePoint : OVERLAYS.keySet()) {
            final int y = 13 - ASCENTS.get(codePoint);
            assertTrue(y >= SlotGeometry.ORIGIN_Y && y < 222,
                    "U+%X declares ascent %d, which puts its top at y = %d - outside the slot area"
                            .formatted(codePoint, ASCENTS.get(codePoint), y));
        }
    }

    @Test
    @DisplayName("a canvas draws every overlay at the x it was given, and ends on the anchor")
    void theCanvasLandsEveryOverlay() {
        final int width = 68;
        final Component surface = MenuTitle.on(Glyphs.GUI_TRAVEL_PANEL)
                .overlay(Glyphs.GUI_TRAVEL_HERE_TOP, 9, width)
                .overlay(Glyphs.GUI_TRAVEL_LOCKED_BOTTOM, 9, width)
                .overlay(Glyphs.GUI_TRAVEL_LOCKED_BOTTOM, 99, width)
                .panel();

        final Map<Integer, List<Integer>> landed = new HashMap<>();
        int cursor = MenuTitle.ANCHOR_X;
        for (final int codePoint : plain(surface).codePoints().toArray()) {
            if (OVERLAYS.containsKey(codePoint) || PANELS.containsKey(codePoint)) {
                landed.computeIfAbsent(codePoint, ignored -> new java.util.ArrayList<>()).add(cursor);
            }
            cursor += ADVANCES.getOrDefault(codePoint,
                    PANELS.getOrDefault(codePoint, OVERLAYS.getOrDefault(codePoint, 0)));
        }

        assertEquals(List.of(0), landed.get(Glyphs.GUI_TRAVEL_PANEL.codePointAt(0)),
                "the panel has to start on the window's left edge");
        assertEquals(List.of(9), landed.get(Glyphs.GUI_TRAVEL_HERE_TOP.codePointAt(0)));
        assertEquals(List.of(99, 9), landed.get(Glyphs.GUI_TRAVEL_LOCKED_BOTTOM.codePointAt(0)),
                "overlays are laid down right to left, whatever order they were added in - the"
                        + " font has no positive advance, so the cursor can only ever walk back");
        assertEquals(MenuTitle.ANCHOR_X, cursor,
                "the surface has to end on the title anchor, or the readable title moves");
    }

    @Test
    @DisplayName("an overlay that does not fit the window is refused")
    void anOverlayOffTheWindowThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> MenuTitle.on(Glyphs.GUI_TRAVEL_PANEL).overlay(Glyphs.GUI_TRAVEL_HERE_TOP, 120, 68));
        assertThrows(IllegalArgumentException.class,
                () -> MenuTitle.on(Glyphs.GUI_TRAVEL_PANEL).overlay(Glyphs.GUI_TRAVEL_HERE_TOP, -1, 68));
    }

    @Test
    @DisplayName("a canvas without overlays is exactly the plain panel")
    void aBareCanvasIsThePanel() {
        assertEquals(plain(MenuTitle.panel(6)), plain(MenuTitle.on(Glyphs.GUI_PANEL_6).panel()));
    }

    @Test
    @DisplayName("the panel is white and names its font; the readable title does neither")
    void theStylesAreSplitTheWayTheClientNeeds() {
        final Component title = MenuTitle.of(3, Component.text("Navigation"));
        final Component panel = title.children().get(0);
        final Component readable = title.children().get(1);

        assertEquals(NamedTextColor.WHITE, panel.color(),
                "vanilla draws an inventory title in hardcoded 0x404040, which applies to any"
                        + " component naming no colour - white art would come out grey");
        assertEquals(Key.key(Glyphs.FONT_GUI), panel.style().font(),
                "the four fonts allocate independently, so a panel code point in minecraft:default"
                        + " draws whatever that font holds at the same code point");
        assertNull(readable.style().font(),
                "the readable half has to render in minecraft:default, which is where the letters"
                        + " are - nordtal:gui carries no ascii sheet at all");
    }

    @Test
    @DisplayName("a row count the pack has no panel for fails loudly")
    void anImpossibleRowCountThrows() {
        for (final int rows : new int[]{0, -1, 7, 54}) {
            assertThrows(IllegalArgumentException.class, () -> MenuTitle.of(rows, Component.empty()),
                    "opening a menu with a missing-glyph box where its frame should be is worse"
                            + " than not opening it");
        }
    }

    @Test
    @DisplayName("a shift of nothing is nothing, and one the font cannot express throws")
    void theShiftIsBoundedByWhatTheFontDeclares() {
        assertEquals("", MenuTitle.shift(0));
        assertThrows(IllegalArgumentException.class, () -> MenuTitle.shift(256));
        assertThrows(IllegalArgumentException.class, () -> MenuTitle.shift(-1));
        for (int pixels = 0; pixels <= 255; pixels++) {
            assertEquals(-pixels, displacement(MenuTitle.shift(pixels)),
                    "shift(" + pixels + ") does not move the cursor " + pixels + " left");
        }
    }

    @Test
    @DisplayName("every glyph the composition uses is one the font actually declares")
    void nothingIsComposedOutOfACodePointTheFontDoesNotHave() {
        final List<String> compositions = new java.util.ArrayList<>();
        for (int rows = 1; rows <= MenuTitle.MAX_ROWS; rows++) {
            compositions.add(plain(MenuTitle.panel(rows)));
        }
        compositions.add(plain(MenuTitle.on(Glyphs.GUI_TRAVEL_PANEL)
                .overlay(Glyphs.GUI_TRAVEL_LOCKED_TOP, 9, 68)
                .overlay(Glyphs.GUI_TRAVEL_LOCKED_BOTTOM, 9, 68)
                .overlay(Glyphs.GUI_TRAVEL_HERE_TOP, 99, 68)
                .overlay(Glyphs.GUI_TRAVEL_HERE_BOTTOM, 99, 68)
                .panel()));
        for (final String composition : compositions) {
            composition.codePoints().forEach(codePoint ->
                    assertTrue(ADVANCES.containsKey(codePoint) || PANELS.containsKey(codePoint)
                                    || OVERLAYS.containsKey(codePoint),
                            "U+%X is composed into a menu title and nordtal:gui does not declare"
                                    .formatted(codePoint) + " it - it reaches the player as a"
                                    + " missing-glyph box in the middle of the frame"));
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    /** What the cursor has moved, in pixels, after drawing this string in {@code nordtal:gui}. */
    private static int displacement(final String composed) {
        return composed.codePoints()
                .map(codePoint -> ADVANCES.getOrDefault(codePoint,
                        PANELS.getOrDefault(codePoint, OVERLAYS.getOrDefault(codePoint, 0))))
                .sum();
    }

    /**
     * The component's own literal, without a serializer.
     *
     * <p>Deliberately not {@code PlainTextComponentSerializer}: that lives in its own artifact,
     * which nothing in this module needs, and the panel is a single {@code TextComponent} whose
     * content <em>is</em> the composition under test.
     */
    private static String plain(final Component component) {
        return ((TextComponent) component).content();
    }

    private static BufferedImage read(final String textureId) {
        final java.nio.file.Path file = FontFile.texturePath(textureId);
        try {
            final BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) {
                throw new IllegalStateException(file + " is not an image ImageIO can read");
            }
            return image;
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
