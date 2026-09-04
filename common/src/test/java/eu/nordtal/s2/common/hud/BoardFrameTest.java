package eu.nordtal.s2.common.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.RepositoryRoot;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BoardFrame} held against the pack rather than against its own constants.
 *
 * <p>Everything here is derived from {@code nordtal/font/board.json} and the PNGs it names: the
 * advance of every code point is read the way the client reads it - a space provider's number, or a
 * bitmap's rightmost non-transparent column plus the two pixels Minecraft adds - and the composed
 * strings are then <em>walked</em> with a cursor. So this test can disagree with the code, which is
 * the only kind of test worth having about a pixel offset.
 *
 * <p>What it cannot say is whether the result looks like a board. Nothing without a client can, and
 * the rehearsal item for it is in the owner's checklist.
 */
class BoardFrameTest {

    private static final String FONT = "resource-pack/src/assets/nordtal/font/board.json";
    private static final String ASSETS = "resource-pack/src/assets";

    /** Code point -> how far the cursor moves after drawing it, the way the client computes it. */
    private static final Map<Integer, Integer> ADVANCES = advances();

    private static final int[] WIDTHS = {BoardFrame.MIN_WIDTH, 100, 180, BoardFrame.MAX_WIDTH};

    @Test
    @DisplayName("the three glyph widths are the pack's, not the code's")
    void theWidthsComeFromTheArt() {
        assertEquals(BoardFrame.CORNER_LEFT_WIDTH, trimmed("ui/board/corner_tl.png"),
                "corner_tl's stub reaches right across its whole 9px cell, which is why the left"
                        + " corner is wider than the right one");
        assertEquals(BoardFrame.CORNER_LEFT_WIDTH, trimmed("ui/board/corner_bl.png"));
        assertEquals(BoardFrame.CORNER_RIGHT_WIDTH, trimmed("ui/board/corner_tr.png"),
                "corner_tr's stub comes in from the left and stops at the cell centre");
        assertEquals(BoardFrame.CORNER_RIGHT_WIDTH, trimmed("ui/board/corner_br.png"));
        assertEquals(BoardFrame.EDGE_V_WIDTH, trimmed("ui/board/edge_v_l.png"));
        assertEquals(BoardFrame.EDGE_V_WIDTH, trimmed("ui/board/edge_v_r.png"));
    }

    @Test
    @DisplayName("every tiling segment is exactly as wide as its name says")
    void theSegmentsTile() {
        for (final int power : new int[] {1, 2, 4, 8, 16, 32, 64, 128}) {
            assertEquals(power, trimmed("ui/board/edge_h_" + power + ".png"),
                    "edge_h_" + power + " has to be " + power + " pixels wide or a border composed"
                            + " from it lands short, and the corner then sits inside the box");
            assertEquals(power, trimmed("ui/board/divider_" + power + ".png"));
        }
    }

    @Test
    @DisplayName("a border's edges start at the content column and end exactly one width later")
    void theBorderSpansTheWidth() {
        for (final int width : WIDTHS) {
            final Walk walk = walk(frameTextOf(BoardFrame.border(width,
                    Glyphs.BOARD_CORNER_TOP_LEFT, Glyphs.BOARD_CORNER_TOP_RIGHT)));

            assertEquals(BoardFrame.CONTENT_X, walk.drawnAt(Glyphs.BOARD_EDGE_H_128.codePointAt(0),
                            Glyphs.BOARD_EDGE_H_64.codePointAt(0),
                            Glyphs.BOARD_EDGE_H_32.codePointAt(0),
                            Glyphs.BOARD_EDGE_H_16.codePointAt(0)),
                    "width " + width + ": the first horizontal edge has to begin where the content"
                            + " column does, or the frame and its text are drawn on two grids");
            assertEquals(BoardFrame.CONTENT_X + width,
                    walk.drawnAt(Glyphs.BOARD_CORNER_TOP_RIGHT.codePointAt(0)),
                    "width " + width + ": the closing corner sits one width past the content"
                            + " column - that is what 'the board is this wide' means");
        }
    }

    @Test
    @DisplayName("a content row leaves the cursor at the content column, whatever the width")
    void theRowReturnsToTheContentColumn() {
        for (final int width : WIDTHS) {
            final Component row = BoardFrame.row(width, Component.text("x"));
            final Walk walk = walk(frameTextOf(row));

            assertEquals(BoardFrame.CONTENT_X, walk.cursor(),
                    "width " + width + ": the content is appended after the frame and nothing"
                            + " shifts it, so the frame has to hand back a cursor at " 
                            + BoardFrame.CONTENT_X + ". This is the invariant the whole class"
                            + " exists for - the content's own width is the one thing that cannot"
                            + " be computed, so nothing may be placed after it.");
            assertEquals(BoardFrame.CONTENT_X + width,
                    walk.drawnAt(Glyphs.BOARD_EDGE_V_RIGHT.codePointAt(0)),
                    "width " + width + ": the right-hand vertical has to line up with the corner"
                            + " above it, or the box is a trapezium");
            assertEquals(0, walk.drawnAt(Glyphs.BOARD_EDGE_V_LEFT.codePointAt(0)),
                    "the left-hand vertical is the first thing on the line");
        }
    }

    @Test
    @DisplayName("every line of a board ends at the same pixel")
    void theBoxIsARectangle() {
        for (final int width : WIDTHS) {
            final Component board = BoardFrame.render(width, Component.text("t"),
                    List.of(Component.text("body")));
            final List<String> lines = frameLines(board);
            // top border, title, divider, one body line, bottom border
            assertEquals(5, lines.size());

            final int expected = BoardFrame.CONTENT_X + width + BoardFrame.EDGE_V_WIDTH;
            for (int index = 0; index < lines.size(); index++) {
                assertEquals(expected, walk(lines.get(index)).end(),
                        "width " + width + ", line " + index + ": every line of the box has to end"
                                + " at the same pixel, or the frame is a trapezium. The corners are"
                                + " " + BoardFrame.CORNER_RIGHT_WIDTH + " wide and the verticals "
                                + BoardFrame.EDGE_V_WIDTH + ", which is the same number for exactly"
                                + " this reason.");
            }
        }
    }

    @Test
    @DisplayName("the rule under the title starts where the border's edges do")
    void theDividerMatchesTheBorders() {
        for (final int width : WIDTHS) {
            final Component board = BoardFrame.render(width, Component.text("t"), List.of());
            final List<String> lines = frameLines(board);
            assertEquals(4, lines.size(), "a board with no body lines is still four lines");

            final Walk divider = walk(lines.get(2));
            assertEquals(BoardFrame.CONTENT_X, divider.drawnAt(
                            Glyphs.BOARD_DIVIDER_128.codePointAt(0),
                            Glyphs.BOARD_DIVIDER_64.codePointAt(0),
                            Glyphs.BOARD_DIVIDER_32.codePointAt(0),
                            Glyphs.BOARD_DIVIDER_16.codePointAt(0)),
                    "width " + width + ": the rule is drawn as the row's content, so it starts at"
                            + " the content column - the same x the border's first edge does");
            assertEquals(BoardFrame.CONTENT_X + width,
                    divider.drawnAt(Glyphs.BOARD_EDGE_V_RIGHT.codePointAt(0)));
        }
    }

    @Test
    @DisplayName("a board is a border, a title, a rule, the lines, and a border")
    void theShapeIsFixed() {
        final Component board = BoardFrame.render(120, Component.text("title"),
                List.of(Component.text("a"), Component.text("b"), Component.text("c")));
        assertEquals(7, frameLines(board).size());
    }

    @Test
    @DisplayName("every code point the frame composes is one the font declares")
    void nothingIsUndrawn() {
        final Walk walk = walk(frameTextOf(BoardFrame.row(180, Component.empty()))
                + frameTextOf(BoardFrame.border(180, Glyphs.BOARD_CORNER_BOTTOM_LEFT,
                        Glyphs.BOARD_CORNER_BOTTOM_RIGHT)));
        assertEquals(List.of(), walk.unknown(),
                "a code point nordtal:board does not declare renders as a missing-glyph box in the"
                        + " middle of the frame, and the cursor arithmetic behind it is wrong too");
    }

    @Test
    @DisplayName("the frame names its font and its colour, and the content names neither")
    void theFrameIsSelfContained() {
        final Component row = BoardFrame.row(180, Component.text("body"));
        final List<Component> parts = new ArrayList<>();
        flatten(row, parts);

        final Component frame = parts.stream()
                .filter(part -> part instanceof TextComponent text && !text.content().isEmpty()
                        && text.content().codePointAt(0) > 0xFFFF)
                .findFirst().orElseThrow();
        assertEquals(Glyphs.FONT_BOARD, String.valueOf(frame.style().font()),
                "a component that names no font resolves its code points in minecraft:default,"
                        + " where they are other glyphs entirely - the U+FE004 collision Glyphs"
                        + " documents");
        assertTrue(frame.style().color() != null,
                "an uncoloured frame inherits the parent's, so a gold heading would drag a gold"
                        + " frame along with it");
    }

    @Test
    @DisplayName("a width the shifts cannot express is refused, not clamped")
    void theWidthIsBounded() {
        assertThrows(IllegalArgumentException.class,
                () -> BoardFrame.render(BoardFrame.MIN_WIDTH - 1, Component.empty(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> BoardFrame.render(BoardFrame.MAX_WIDTH + 1, Component.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> BoardFrame.left(256));
        assertThrows(IllegalArgumentException.class, () -> BoardFrame.right(-1));
    }

    @Test
    @DisplayName("the widest board still fits inside the leftward shift the font can express")
    void theCeilingIsTheShiftsCeiling() {
        assertEquals(255, 128 + 64 + 32 + 16 + 8 + 4 + 2 + 1);
        assertTrue(BoardFrame.MAX_WIDTH + BoardFrame.EDGE_V_WIDTH <= 255,
                "a content row walks back width + " + BoardFrame.EDGE_V_WIDTH + " pixels, and the"
                        + " eight negative advances reach 255. MAX_WIDTH is derived from that and"
                        + " is not a taste question.");
    }

    // --- the client's own arithmetic ---------------------------------------------------------

    /** A composed string walked with a cursor, the way the client lays it out. */
    private record Walk(int cursor, int end, Map<Integer, Integer> firstDrawnAt,
                        List<String> unknown) {

        /** Where the first of these code points was drawn. */
        int drawnAt(final int... codePoints) {
            for (final int codePoint : codePoints) {
                final Integer at = firstDrawnAt.get(codePoint);
                if (at != null) {
                    return at;
                }
            }
            throw new AssertionError("none of those code points is in the composition");
        }
    }

    private static Walk walk(final String composed) {
        int cursor = 0;
        int end = 0;
        final Map<Integer, Integer> firstDrawnAt = new LinkedHashMap<>();
        final List<String> unknown = new ArrayList<>();

        for (int index = 0; index < composed.length(); ) {
            final int codePoint = composed.codePointAt(index);
            index += Character.charCount(codePoint);

            final Integer advance = ADVANCES.get(codePoint);
            if (advance == null) {
                unknown.add(String.format("U+%X", codePoint));
                continue;
            }
            if (advance > 0 && !isSpace(codePoint)) {
                firstDrawnAt.putIfAbsent(codePoint, cursor);
                // One past the rightmost pixel this glyph paints - the advance without the
                // separator Minecraft adds. Comparable across lines, which is the point.
                end = Math.max(end, cursor + advance - 1);
            }
            cursor += advance;
        }
        return new Walk(cursor, end, firstDrawnAt, unknown);
    }

    private static boolean isSpace(final int codePoint) {
        return codePoint >= 0xFF000 && codePoint <= 0xFFFFF;
    }

    private static Map<Integer, Integer> advances() {
        final Map<Integer, Integer> out = new LinkedHashMap<>();
        final JsonObject root = JsonParser.parseString(RepositoryRoot.read(FONT)).getAsJsonObject();
        for (final JsonElement element : root.getAsJsonArray("providers")) {
            final JsonObject provider = element.getAsJsonObject();
            if ("space".equals(provider.get("type").getAsString())) {
                for (final Map.Entry<String, JsonElement> advance
                        : provider.getAsJsonObject("advances").entrySet()) {
                    out.put(advance.getKey().codePointAt(0), advance.getValue().getAsInt());
                }
                continue;
            }
            final String file = provider.get("file").getAsString();
            final int height = provider.get("height").getAsInt();
            final BufferedImage image = read(texture(file));
            // The client scales the sheet to the provider's height; every board glyph is drawn 1:1
            // and this test would notice if one stopped being.
            assertEquals(height, image.getHeight(), file + " is not drawn at its provider's height");
            // Rightmost column with any alpha, plus one for the column itself, plus the one pixel
            // of separator Minecraft adds to every bitmap glyph. That last pixel is the whole
            // reason this font carries a negative-advance space provider at all.
            final int advance = rightmost(image) + 2;
            for (final JsonElement row : provider.getAsJsonArray("chars")) {
                row.getAsString().codePoints().forEach(codePoint -> out.put(codePoint, advance));
            }
        }
        return Map.copyOf(out);
    }

    private static int trimmed(final String relative) {
        return rightmost(read(RepositoryRoot.resolve(ASSETS).resolve("nordtal/textures")
                .resolve(relative))) + 1;
    }

    private static int rightmost(final BufferedImage image) {
        int last = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = image.getWidth() - 1; x > last; x--) {
                if ((image.getRGB(x, y) >>> 24) > 0) {
                    last = x;
                }
            }
        }
        return last;
    }

    private static Path texture(final String textureId) {
        final int colon = textureId.indexOf(':');
        return RepositoryRoot.resolve(ASSETS).resolve(textureId.substring(0, colon))
                .resolve("textures").resolve(textureId.substring(colon + 1));
    }

    private static BufferedImage read(final Path path) {
        try {
            final BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) {
                throw new IllegalStateException(path + " is not an image ImageIO can read");
            }
            return image;
        } catch (final IOException exception) {
            throw new UncheckedIOException("cannot read " + path, exception);
        }
    }

    // --- reading a composed component back ---------------------------------------------------

    /** Every line of a rendered board, as the frame text that opens it. */
    private static List<String> frameLines(final Component board) {
        final List<Component> parts = new ArrayList<>();
        flatten(board, parts);

        final List<String> lines = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        for (final Component part : parts) {
            if (!(part instanceof TextComponent text)) {
                continue;
            }
            if ("\n".equals(text.content())) {
                lines.add(current.toString());
                current.setLength(0);
                continue;
            }
            if (isFrame(part)) {
                current.append(text.content());
            }
        }
        lines.add(current.toString());
        return lines;
    }

    /** The frame half of one row - everything that names {@code nordtal:board}. */
    private static String frameTextOf(final Component component) {
        final List<Component> parts = new ArrayList<>();
        flatten(component, parts);
        final StringBuilder out = new StringBuilder();
        for (final Component part : parts) {
            if (isFrame(part) && part instanceof TextComponent text) {
                out.append(text.content());
            }
        }
        return out.toString();
    }

    private static boolean isFrame(final Component component) {
        return Glyphs.FONT_BOARD.equals(String.valueOf(component.style().font()));
    }

    private static void flatten(final Component component, final List<Component> out) {
        out.add(component);
        component.children().forEach(child -> flatten(child, out));
    }
}
