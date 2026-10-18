package eu.nordtal.season.smp.npc;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.papercommon.menu.MenuFont;
import eu.nordtal.season.papercommon.menu.MenuPalette;
import eu.nordtal.season.papercommon.menu.MenuTitle;
import eu.nordtal.season.papercommon.menu.SlotGeometry;
import java.util.List;
import net.kyori.adventure.text.Component;

/** Draws the objective menu: a heading, up to {@link #CARDS_PER_PAGE} objective cards and the player's share. */
public final class ObjectivePanel {

    public static final int ROWS = MenuTitle.MAX_ROWS;

    public static final int HEADING_ROW = 0;

    public static final int SHARE_ROW = ROWS - 1;

    public static final int CARDS_PER_PAGE = 4;

    /** The distance in pixels between a drawn element and the slot cells it covers. */
    public static final int INSET = 2;

    public static final int PILL_X = SlotGeometry.ORIGIN_X + INSET;
    public static final int PILL_WIDTH = SlotGeometry.COLUMNS * SlotGeometry.PITCH - 2 * INSET;

    private static final int PILL_TEXT_X = PILL_X + 3;
    private static final int PILL_RIGHT = PILL_X + PILL_WIDTH - 3;

    private static final int HEADING_BAR_X = 104;

    public static final int HEADING_BAR_WIDTH = 6;

    private static final int ICON_SIZE = 8;

    private static final int SHARE_ICON_X = PILL_X + 3;
    private static final int SHARE_TEXT_X = SHARE_ICON_X + ICON_SIZE + 5;

    public static final int CARD_WIDTH = 4 * SlotGeometry.PITCH - 2 * INSET;

    public static final int CARD_HEIGHT = 2 * SlotGeometry.PITCH - 2 * INSET;

    /** The x positions of the left and the right card column. Slot column 4 stays empty. */
    public static final List<Integer> CARD_X = List.of(SlotGeometry.x(0) + INSET, SlotGeometry.x(5) + INSET);

    /** The chest row of the upper half of each card row. The lower half is on the next row. */
    public static final List<Integer> CARD_ROW = List.of(1, 3);

    private static final int CARD_ICON_DX = 3;
    private static final int CARD_NAME_DX = 15;
    private static final int CARD_NUMBERS_DX = 3;

    public static final int CARD_NAME_WIDTH = CARD_WIDTH - CARD_NAME_DX - 1;

    private static final int CARD_BAR_DX = 4;

    public static final int BAR_MAX = CARD_WIDTH - 2 * CARD_ICON_DX - 2;

    /** The widths in pixels of the glyphs of {@link Glyphs#GUI_BAR_FILL_TOP}, in the order of that list. */
    private static final List<Integer> BAR_FILL_WIDTHS = List.of(1, 2, 4, 8, 16, 32);

    private static final int BUTTON_WIDTH = SlotGeometry.PITCH - 2 * INSET;
    private static final int PREV_X = SlotGeometry.x(7) + INSET;
    private static final int NEXT_X = SlotGeometry.x(8) + INSET;

    private static final int PAGED_RIGHT = SlotGeometry.x(7) - 2;

    /** The width of the share plate on a paged menu. It ends where the page buttons begin. */
    public static final int PILL_SHORT_WIDTH = 7 * SlotGeometry.PITCH - 2 * INSET;

    public static final int PREV_SLOT = SlotGeometry.slot(7, SHARE_ROW);
    public static final int NEXT_SLOT = SlotGeometry.slot(8, SHARE_ROW);

    private ObjectivePanel() {}

    /**
     * @param icon    one of the {@code GUI_ROW_ICON_*} glyphs
     * @param name    the objective's name, which {@link #title} folds and shortens
     * @param numbers the text under the bar, for example {@code 1240/2048}
     * @param ratio   the fill of the bar, from 0 to 1
     * @param done    whether the card is drawn with the green overlay
     */
    public record Card(String icon, String name, String numbers, double ratio, boolean done) {}

    public static String icon(final eu.nordtal.season.smp.milestone.ObjectiveType type, final boolean done) {
        if (done) {
            return Glyphs.GUI_ROW_ICON_DONE;
        }
        return switch (type) {
            case HAND_IN -> Glyphs.GUI_ROW_ICON_HAND_IN;
            case STATISTIC -> Glyphs.GUI_ROW_ICON_STATISTIC;
            case ADVANCEMENT -> Glyphs.GUI_ROW_ICON_ADVANCEMENT;
        };
    }

    /**
     * Draws the menu into the inventory title.
     *
     * @param title     the translated window title
     * @param milestone the milestone name in the heading
     * @param bar       the text bar in the heading, {@link #HEADING_BAR_WIDTH} characters
     * @param counter   the text at the right edge of the heading, for example {@code 1/4}
     * @param cards     at most {@link #CARDS_PER_PAGE} cards, top row first, left to right
     * @param share     the share sentence in the bottom row
     * @param hasPrev   whether a page precedes this one
     * @param hasNext   whether a page follows this one
     */
    public static Component title(
            final Component title,
            final String milestone,
            final String bar,
            final String counter,
            final List<Card> cards,
            final String share,
            final boolean hasPrev,
            final boolean hasNext) {
        if (cards.size() > CARDS_PER_PAGE) {
            throw new IllegalArgumentException("a page holds " + CARDS_PER_PAGE + " cards, not " + cards.size());
        }
        final MenuTitle.Canvas canvas = MenuTitle.onPlain(ROWS);

        heading(canvas, milestone, bar, counter);
        for (int index = 0; index < cards.size(); index++) {
            card(canvas, index, cards.get(index));
        }
        share(canvas, share, hasPrev, hasNext);

        return canvas.build(title);
    }

    private static void heading(
            final MenuTitle.Canvas canvas, final String milestone, final String bar, final String counter) {
        canvas.rowArt(Glyphs.GUI_ROW_PILL_DARK, HEADING_ROW, PILL_X, null);

        final String folded = MenuFont.fold(counter);
        canvas.rowTextRight(folded, HEADING_ROW, PILL_RIGHT, MenuPalette.INK);
        canvas.rowText(
                MenuFont.fit(bar, PILL_RIGHT - MenuFont.width(folded) - 4 - HEADING_BAR_X),
                HEADING_ROW,
                HEADING_BAR_X,
                MenuPalette.PROGRESS);
        canvas.rowText(
                MenuFont.fit(milestone, HEADING_BAR_X - 4 - PILL_TEXT_X), HEADING_ROW, PILL_TEXT_X, MenuPalette.INK);
    }

    private static void card(final MenuTitle.Canvas canvas, final int index, final Card card) {
        final int x = CARD_X.get(index % 2);
        final int upper = CARD_ROW.get(index / 2);
        final int lower = upper + 1;
        final boolean top = index < 2;

        canvas.overlay(top ? Glyphs.GUI_CARD_TOP : Glyphs.GUI_CARD_BOTTOM, x, CARD_WIDTH);
        canvas.rowArt(card.icon(), upper, x + CARD_ICON_DX, MenuPalette.INK);
        canvas.rowText(MenuFont.fit(card.name(), CARD_NAME_WIDTH), upper, x + CARD_NAME_DX, MenuPalette.INK);
        fill(canvas, x + CARD_BAR_DX, top, card.ratio());
        canvas.rowText(MenuFont.fit(card.numbers(), CARD_NAME_WIDTH), lower, x + CARD_NUMBERS_DX, MenuPalette.SOFT);

        if (card.done()) {
            canvas.overlay(top ? Glyphs.GUI_CARD_DONE_TOP : Glyphs.GUI_CARD_DONE_BOTTOM, x, CARD_WIDTH);
        }
    }

    /** Draws the bar fill from {@code x} as slices of power-of-two widths. A ratio above 0 draws at least one pixel. */
    private static void fill(final MenuTitle.Canvas canvas, final int x, final boolean top, final double ratio) {
        final double clamped = Math.max(0.0, Math.min(1.0, ratio));
        int width = (int) Math.floor(clamped * BAR_MAX);
        if (width == 0 && clamped > 0.0) {
            width = 1;
        }
        final List<String> glyphs = top ? Glyphs.GUI_BAR_FILL_TOP : Glyphs.GUI_BAR_FILL_BOTTOM;
        int at = x;
        for (int index = BAR_FILL_WIDTHS.size() - 1; index >= 0; index--) {
            final int step = BAR_FILL_WIDTHS.get(index);
            if (width >= step) {
                canvas.overlay(glyphs.get(index), at, step);
                width -= step;
                at += step;
            }
        }
    }

    private static void share(
            final MenuTitle.Canvas canvas, final String share, final boolean hasPrev, final boolean hasNext) {
        final boolean paged = hasPrev || hasNext;
        // A paged menu uses the short plate, which ends at the seventh cell.
        canvas.rowArt(paged ? Glyphs.GUI_ROW_PILL_SHORT : Glyphs.GUI_ROW_PILL, SHARE_ROW, PILL_X, null);
        canvas.rowArt(Glyphs.GUI_ROW_ICON_AURA, SHARE_ROW, SHARE_ICON_X, MenuPalette.INK);

        canvas.rowText(
                MenuFont.fit(share, (paged ? PAGED_RIGHT : PILL_RIGHT) - SHARE_TEXT_X),
                SHARE_ROW,
                SHARE_TEXT_X,
                MenuPalette.INK);
        if (!paged) {
            return;
        }
        pageButton(canvas, PREV_X, Glyphs.GUI_ROW_ICON_PREV, hasPrev);
        pageButton(canvas, NEXT_X, Glyphs.GUI_ROW_ICON_NEXT, hasNext);
    }

    /** Draws a page button. The button is greyed out if {@code enabled} is false. */
    private static void pageButton(
            final MenuTitle.Canvas canvas, final int x, final String arrow, final boolean enabled) {
        canvas.rowArt(enabled ? Glyphs.GUI_ROW_BUTTON_SMALL : Glyphs.GUI_ROW_BUTTON_SMALL_OFF, SHARE_ROW, x, null);
        canvas.rowArt(
                arrow, SHARE_ROW, x + (BUTTON_WIDTH - ICON_SIZE) / 2, enabled ? MenuPalette.INK : MenuPalette.DISABLED);
    }

    public static List<Integer> slotsOf(final int index) {
        final int firstColumn = index % 2 == 0 ? 0 : 5;
        final int upper = CARD_ROW.get(index / 2);
        final List<Integer> slots = new java.util.ArrayList<>(8);
        for (int row = upper; row <= upper + 1; row++) {
            for (int column = firstColumn; column < firstColumn + 4; column++) {
                slots.add(SlotGeometry.slot(column, row));
            }
        }
        return List.copyOf(slots);
    }

    /** Returns the index of the card that covers {@code slot}, or -1. */
    public static int cardOf(final int slot) {
        for (int index = 0; index < CARDS_PER_PAGE; index++) {
            if (slotsOf(index).contains(slot)) {
                return index;
            }
        }
        return -1;
    }
}
