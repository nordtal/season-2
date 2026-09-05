package eu.nordtal.s2.common.hud;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.hud.BossBarLine.Pill;
import eu.nordtal.s2.common.pack.PackAdvances;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walks a composed HUD line with a cursor driven by the pack's own advances, so it can contradict
 * {@link BossBarLine} rather than restate it.
 *
 * <p>What is pinned is the property the pills rest on: every piece of content sits <em>inside</em>
 * its pill, {@link BossBarLine#PADDING} in from either cap, and the line ends exactly at the last
 * pill's right edge - because the client centres a boss bar name by its total width, and a total
 * width that is off by the pixels of a stray advance is a line that sits off-centre for ever.</p>
 */
class BossBarLineTest {

    private static final Map<Integer, Integer> ADVANCES =
            PackAdvances.of("resource-pack/src/assets/nordtal/font/bossbar.json");

    /**
     * A drawn run of glyphs, in line pixels: the first column it paints and the last.
     *
     * <p>A glyph paints {@code advance - 1} columns and the client adds one more of nothing, so the
     * last painted column is {@code cursor + advance - 2} - the pixel a person sees, which is what
     * the padding is measured against.</p>
     */
    private record Span(String what, int start, int end) {
    }

    @Test
    @DisplayName("each pill's content sits PADDING in from its caps, and the line ends on the last pill")
    void everyPillHoldsItsContent() {
        final List<Pill> pills = List.of(
                Pill.of(Glyphs.BOSSBAR_ICON_DIM_OVERWORLD, "Nordtal"),
                Pill.of("Ein Meilenstein mit Umlauten - 42%"),
                Pill.of(Glyphs.BOSSBAR_ARROW_090_0, ""));

        final Walk walk = walk(BossBarLine.compose(pills));
        assertEquals(pills.size(), walk.pills.size(), "one background per pill");
        assertEquals(pills.size(), walk.contents.size(), "one run of content per pill");

        for (int index = 0; index < pills.size(); index++) {
            final Span pill = walk.pills.get(index);
            final Span content = walk.contents.get(index);
            assertEquals(pill.start + BossBarWidth.CAP + BossBarLine.PADDING, content.start,
                    "pill " + index + "'s content does not start PADDING in from the left cap");
            assertEquals(pill.end - BossBarWidth.CAP - BossBarLine.PADDING, content.end,
                    "pill " + index + "'s content does not end PADDING before the right cap - the"
                            + " pill is the wrong width for what it holds");
            if (index > 0) {
                assertEquals(walk.pills.get(index - 1).end + 1 + BossBarLine.GAP, pill.start,
                        "pill " + index + " is not GAP after the one before it");
            }
        }
        assertEquals(walk.pills.get(pills.size() - 1).end + 1, walk.cursor,
                "the cursor has to finish one past the last pill's last column: the client centres the"
                        + " whole line by its total advance, so anything past it shifts every pill");
    }

    @Test
    @DisplayName("an icon-only pill and a text-only pill are both just their content plus padding")
    void theTwoDegenerateShapes() {
        final Walk icon = walk(BossBarLine.compose(List.of(Pill.of(Glyphs.BOSSBAR_ICON_COMPASS, ""))));
        assertEquals(ADVANCES.get(Glyphs.BOSSBAR_ICON_COMPASS.codePointAt(0)) - 2,
                icon.contents.get(0).end - icon.contents.get(0).start,
                "an icon-only pill must not carry the icon gap after a text that is not there");

        final Walk text = walk(BossBarLine.compose(List.of(Pill.of("12"))));
        assertEquals(BossBarAdvances.width("12") - 2, text.contents.get(0).end - text.contents.get(0).start);
    }

    @Test
    @DisplayName("the component names the bossbar font and turns the shadow off")
    void theComponentIsStyledTheWayTheHudNeeds() {
        final Component line = BossBarLine.render(List.of(Pill.of("x")));
        assertEquals(Key.key(Glyphs.FONT_BOSSBAR), line.style().font(),
                "without the font key the segments resolve against minecraft:default, where U+E004"
                        + " is the admin tag and not a background tile");
        assertEquals(ShadowColor.none(), line.style().shadowColor(),
                "the client draws every glyph a second time one pixel down and right; on butted"
                        + " tiles that second copy is a dark seam at every boundary");
        assertNull(line.style().color(), "the line is drawn in the client's default white so the"
                + " icons' own colours reach the screen unmultiplied");
        assertEquals(BossBarLine.compose(List.of(Pill.of("x"))), ((TextComponent) line).content());
    }

    @Test
    @DisplayName("a shift is exact in both directions, and left has no ceiling")
    void theShiftsAreExact() {
        for (int pixels = 0; pixels < 700; pixels += 7) {
            assertEquals(-pixels, displacement(BossBarLine.left(pixels)));
        }
        for (int pixels = 0; pixels < 60; pixels++) {
            assertEquals(pixels, displacement(BossBarLine.right(pixels)));
        }
    }

    // --- the walk ------------------------------------------------------------------------

    private record Walk(List<Span> pills, List<Span> contents, int cursor) {
    }

    /**
     * Replays the composition the way the client lays it out. A pill's background is the run from
     * a START cap to the END cap; content is every drawn glyph that is not a background tile.
     */
    private static Walk walk(final String composed) {
        final List<Span> pills = new ArrayList<>();
        final List<Span> contents = new ArrayList<>();
        int cursor = 0;
        int pillStart = -1;
        int contentStart = -1;
        int contentEnd = -1;
        for (final int codePoint : composed.codePoints().toArray()) {
            final Integer advance = ADVANCES.get(codePoint);
            if (advance == null) {
                throw new AssertionError("U+%X is not in bossbar.json".formatted(codePoint));
            }
            if (codePoint == Glyphs.BOSSBAR_BG_START.codePointAt(0)) {
                if (contentStart >= 0) {
                    contents.add(new Span("content", contentStart, contentEnd));
                    contentStart = -1;
                }
                pillStart = cursor;
            } else if (codePoint == Glyphs.BOSSBAR_BG_END.codePointAt(0)) {
                pills.add(new Span("pill", pillStart, cursor + advance - 2));
            } else if (advance > 0 && !isBackground(codePoint) && !isSpace(codePoint)) {
                if (contentStart < 0) {
                    contentStart = cursor;
                }
                contentEnd = cursor + advance - 2;
            }
            cursor += advance;
        }
        if (contentStart >= 0) {
            contents.add(new Span("content", contentStart, contentEnd));
        }
        return new Walk(pills, contents, cursor);
    }

    /** The font's space providers: the ordinary space and the two SPUA-A advance blocks. */
    private static boolean isSpace(final int codePoint) {
        return codePoint == ' ' || codePoint >= 0xFF000;
    }

    private static boolean isBackground(final int codePoint) {
        for (final String tile : new String[]{Glyphs.BOSSBAR_BG_1, Glyphs.BOSSBAR_BG_2, Glyphs.BOSSBAR_BG_4,
                Glyphs.BOSSBAR_BG_8, Glyphs.BOSSBAR_BG_16, Glyphs.BOSSBAR_BG_32, Glyphs.BOSSBAR_BG_64,
                Glyphs.BOSSBAR_BG_128}) {
            if (tile.codePointAt(0) == codePoint) {
                return true;
            }
        }
        return false;
    }

    private static int displacement(final String composed) {
        return composed.codePoints().map(ADVANCES::get).sum();
    }
}
