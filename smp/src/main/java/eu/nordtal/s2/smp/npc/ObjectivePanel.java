package eu.nordtal.s2.smp.npc;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.menu.MenuFont;
import eu.nordtal.s2.common.menu.MenuPalette;
import eu.nordtal.s2.common.menu.MenuTitle;
import eu.nordtal.s2.common.menu.SlotGeometry;

import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * Draws the spawn NPC's surface: the active milestone, four objective cards, and your own share.
 *
 * <h2>What the picture is</h2>
 * Design {@code O3} from the owner's menu artifact - the variant that carries the player's own
 * contribution, which {@code docs/smp.md} names as content of this menu and which no code has ever
 * shown. Six rows: a darker heading plate naming the milestone with a text bar and an
 * "n of m done" counter; two rows of two cards, each 68 x 32 with a type icon, a name, a painted
 * progress bar and its numbers; and a share line along the bottom.
 *
 * <h2>Why a card is a {@code nordtal:gui} glyph and everything on it is a row glyph</h2>
 * A card spans two chest rows and a row font's whole purpose is one ascent per layer per row - so
 * the plate needs a code point per card row ({@link Glyphs#GUI_CARD_TOP}, {@code _BOTTOM}). What
 * sits <em>on</em> the card does not: the icon and the name fall inside the upper row's own icon and
 * text bands, the numbers inside the lower row's text band, so those cost nothing per card. The bar
 * is the one thing in between - it lands in the four-pixel gap between two row bands - which is why
 * its fill has an ascent of its own too.
 *
 * <h2>The bar is painted, and it is six glyphs</h2>
 * The track is baked into the card, so an empty bar costs nothing. The fill is a power-of-two
 * decomposition of its width, exactly as the board frame's edges are: any fill from 0 to
 * {@link #BAR_MAX} is at most four glyphs, because 60 is 32 + 16 + 8 + 4. The alternative the
 * artifact offers - a text bar out of {@code ProgressBar} - is what the <em>heading</em> uses, where
 * there is no room for a painted one beside the name and the counter.
 *
 * <h2>Four cards, and a milestone that has five</h2>
 * The design is four cards and the shipped track's last three milestones have <b>five</b>
 * objectives. Dropping the fifth is the one outcome this menu must not produce - nobody reports an
 * objective they were never shown - so {@link #CARDS_PER_PAGE} is a page and not a limit, and
 * {@link ObjectiveGui} pages. The two page buttons sit on the share row's last two slot cells and
 * are drawn only when there is a second page, so a four-objective milestone is exactly the
 * artifact's drawing and nothing else. That the design has no answer for five is the owner's to
 * settle; this is the reading of it that hides nothing.
 */
public final class ObjectivePanel {

    /** The window is always six rows, whatever the milestone holds. */
    public static final int ROWS = MenuTitle.MAX_ROWS;

    /** The heading plate's row: which milestone this is, and how much of it is done. */
    public static final int HEADING_ROW = 0;

    /** The share line's row: what the player themselves has contributed. */
    public static final int SHARE_ROW = ROWS - 1;

    /** Cards on one page: two rows of two, the artifact's own layout. */
    public static final int CARDS_PER_PAGE = 4;

    /** Pixels between a piece of furniture and the slot cells that make it clickable. */
    public static final int INSET = 2;

    // --- the two full-width plates -------------------------------------------------------
    public static final int PILL_X = SlotGeometry.ORIGIN_X + INSET;
    public static final int PILL_WIDTH = SlotGeometry.COLUMNS * SlotGeometry.PITCH - 2 * INSET;

    private static final int PILL_TEXT_X = PILL_X + 3;
    private static final int PILL_RIGHT = PILL_X + PILL_WIDTH - 3;

    /** Where the heading's six-character text bar starts - the artifact's own x. */
    private static final int HEADING_BAR_X = 104;

    /** How many characters that bar is. Six fits between the name and the counter and no more. */
    public static final int HEADING_BAR_WIDTH = 6;

    /** The icon on the share line, and where the sentence beside it starts. */
    private static final int ICON_SIZE = 8;
    private static final int SHARE_ICON_X = PILL_X + 3;
    private static final int SHARE_TEXT_X = SHARE_ICON_X + ICON_SIZE + 5;

    // --- a card --------------------------------------------------------------------------
    /** Four slot columns inset two: the same 68 pixels a balloon card is wide. */
    public static final int CARD_WIDTH = 4 * SlotGeometry.PITCH - 2 * INSET;

    /** Two slot rows inset two - the balloon's card is three. */
    public static final int CARD_HEIGHT = 2 * SlotGeometry.PITCH - 2 * INSET;

    /** The two x positions a card sits at; slot column 4 is the gap, as it is on the balloon. */
    public static final int[] CARD_X = {
            SlotGeometry.x(0) + INSET, SlotGeometry.x(5) + INSET,
    };

    /** The chest row a card's <em>upper</em> half sits on; its lower half is the row after. */
    public static final int[] CARD_ROW = {1, 3};

    private static final int CARD_ICON_DX = 3;
    private static final int CARD_NAME_DX = 15;
    private static final int CARD_NUMBERS_DX = 3;

    /** The room a card's name has: from its own x to one pixel inside the card's right edge. */
    public static final int CARD_NAME_WIDTH = CARD_WIDTH - CARD_NAME_DX - 1;

    /** The bar's fill starts one pixel inside the track, which starts three inside the card. */
    private static final int CARD_BAR_DX = 4;

    /** The widest the fill can be: the 62px track less its own two edge pixels. */
    public static final int BAR_MAX = CARD_WIDTH - 2 * CARD_ICON_DX - 2;

    // --- the page controls, drawn only when there is a second page ------------------------
    private static final int BUTTON_WIDTH = SlotGeometry.PITCH - 2 * INSET;
    private static final int PREV_X = SlotGeometry.x(7) + INSET;
    private static final int NEXT_X = SlotGeometry.x(8) + INSET;

    /** Where the share sentence has to stop when the page controls are there. */
    private static final int PAGED_RIGHT = SlotGeometry.x(7) - 2;

    public static final int PREV_SLOT = SlotGeometry.slot(7, SHARE_ROW);
    public static final int NEXT_SLOT = SlotGeometry.slot(8, SHARE_ROW);

    private ObjectivePanel() {
    }

    /**
     * One drawn card.
     *
     * @param icon    one of the four {@code GUI_ROW_ICON_*} states - the icon <em>is</em> the state
     * @param name    the objective's name, folded and shortened here
     * @param numbers what stands under the bar, e.g. {@code 1240/2048}
     * @param ratio   0 to 1; the painted bar's width comes from this and from nothing else
     * @param done    whether the green wash goes over it
     */
    public record Card(String icon, String name, String numbers, double ratio, boolean done) {
    }

    /** The pictogram that says what kind of objective this is - or that it is finished. */
    public static String icon(final eu.nordtal.s2.smp.milestone.ObjectiveType type,
                              final boolean done) {
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
     * The whole surface, as the inventory title.
     *
     * @param title     the readable window title, already translated
     * @param milestone what the heading names
     * @param bar       the heading's text bar, {@link #HEADING_BAR_WIDTH} characters
     * @param counter   what stands at the heading's right edge, e.g. {@code 1/4}
     * @param cards     up to {@link #CARDS_PER_PAGE} cards, top-left first then top-right
     * @param share     the sentence along the bottom
     * @param hasPrev   whether there is a page of cards before this one
     * @param hasNext   whether there is a page after this one
     */
    public static Component title(final Component title, final String milestone, final String bar,
                                  final String counter, final List<Card> cards, final String share,
                                  final boolean hasPrev, final boolean hasNext) {
        if (cards.size() > CARDS_PER_PAGE) {
            throw new IllegalArgumentException(
                    "a page holds " + CARDS_PER_PAGE + " cards, not " + cards.size());
        }
        final MenuTitle.Canvas canvas = MenuTitle.onPlain(ROWS);

        heading(canvas, milestone, bar, counter);
        for (int index = 0; index < cards.size(); index++) {
            card(canvas, index, cards.get(index));
        }
        share(canvas, share, hasPrev, hasNext);

        return canvas.build(title);
    }

    private static void heading(final MenuTitle.Canvas canvas, final String milestone,
                                final String bar, final String counter) {
        canvas.rowArt(Glyphs.GUI_ROW_PILL_DARK, HEADING_ROW, PILL_X, null);

        final String folded = MenuFont.fold(counter);
        canvas.rowTextRight(folded, HEADING_ROW, PILL_RIGHT, MenuPalette.INK);
        canvas.rowText(MenuFont.fit(bar, PILL_RIGHT - MenuFont.width(folded) - 4 - HEADING_BAR_X),
                HEADING_ROW, HEADING_BAR_X, MenuPalette.PROGRESS);
        canvas.rowText(MenuFont.fit(milestone, HEADING_BAR_X - 4 - PILL_TEXT_X),
                HEADING_ROW, PILL_TEXT_X, MenuPalette.INK);
    }

    /**
     * One card: its plate, then everything on it, then the wash if it is finished.
     *
     * <p>Order is draw order and it matters twice. The plate is laid first because everything on it
     * would otherwise be painted over; the wash is laid last because it is the one thing that has to
     * tint what is under it, the bar included - a finished objective's bar is full, and washing only
     * the heading would say the card was half settled.</p>
     */
    private static void card(final MenuTitle.Canvas canvas, final int index, final Card card) {
        final int x = CARD_X[index % 2];
        final int upper = CARD_ROW[index / 2];
        final int lower = upper + 1;
        final boolean top = index < 2;

        canvas.overlay(top ? Glyphs.GUI_CARD_TOP : Glyphs.GUI_CARD_BOTTOM, x, CARD_WIDTH);
        canvas.rowArt(card.icon(), upper, x + CARD_ICON_DX, MenuPalette.INK);
        canvas.rowText(MenuFont.fit(card.name(), CARD_NAME_WIDTH), upper, x + CARD_NAME_DX,
                MenuPalette.INK);
        fill(canvas, x + CARD_BAR_DX, top, card.ratio());
        canvas.rowText(MenuFont.fit(card.numbers(), CARD_NAME_WIDTH), lower, x + CARD_NUMBERS_DX,
                MenuPalette.SOFT);

        if (card.done()) {
            canvas.overlay(top ? Glyphs.GUI_CARD_DONE_TOP : Glyphs.GUI_CARD_DONE_BOTTOM, x,
                    CARD_WIDTH);
        }
    }

    /**
     * The painted fill, as a run of power-of-two slices starting at {@code x}.
     *
     * <p>Largest first, so the run is the number's binary representation and there is exactly one
     * way to write any width. A ratio that has started but rounds to nothing still draws one pixel,
     * for the reason {@code ProgressBar} floors to one character: "1 of 3000" must not look like
     * "not begun".</p>
     */
    private static void fill(final MenuTitle.Canvas canvas, final int x, final boolean top,
                             final double ratio) {
        final double clamped = Math.max(0.0, Math.min(1.0, ratio));
        int width = (int) Math.floor(clamped * BAR_MAX);
        if (width == 0 && clamped > 0.0) {
            width = 1;
        }
        final String[] glyphs = top ? Glyphs.GUI_BAR_FILL_TOP : Glyphs.GUI_BAR_FILL_BOTTOM;
        int at = x;
        for (int index = Glyphs.GUI_BAR_FILL_WIDTHS.length - 1; index >= 0; index--) {
            final int step = Glyphs.GUI_BAR_FILL_WIDTHS[index];
            if (width >= step) {
                canvas.overlay(glyphs[index], at, step);
                width -= step;
                at += step;
            }
        }
    }

    private static void share(final MenuTitle.Canvas canvas, final String share,
                              final boolean hasPrev, final boolean hasNext) {
        canvas.rowArt(Glyphs.GUI_ROW_PILL, SHARE_ROW, PILL_X, null);
        canvas.rowArt(Glyphs.GUI_ROW_ICON_AURA, SHARE_ROW, SHARE_ICON_X, MenuPalette.INK);

        final boolean paged = hasPrev || hasNext;
        canvas.rowText(MenuFont.fit(share, (paged ? PAGED_RIGHT : PILL_RIGHT) - SHARE_TEXT_X),
                SHARE_ROW, SHARE_TEXT_X, MenuPalette.INK);
        if (!paged) {
            return;
        }
        pageButton(canvas, PREV_X, Glyphs.GUI_ROW_ICON_PREV, hasPrev);
        pageButton(canvas, NEXT_X, Glyphs.GUI_ROW_ICON_NEXT, hasNext);
    }

    /**
     * One page button, drawn greyed when there is no page on that side.
     *
     * <p>The same rule {@code NavigatePanel} states: a control that vanishes moves nothing, but a
     * player who saw it a second ago has to work out whether it was ever there. The click on a
     * greyed one is refused with the refusal sound.</p>
     */
    private static void pageButton(final MenuTitle.Canvas canvas, final int x, final String arrow,
                                   final boolean enabled) {
        canvas.rowArt(enabled ? Glyphs.GUI_ROW_BUTTON_SMALL : Glyphs.GUI_ROW_BUTTON_SMALL_OFF,
                SHARE_ROW, x, null);
        canvas.rowArt(arrow, SHARE_ROW, x + (BUTTON_WIDTH - ICON_SIZE) / 2,
                enabled ? MenuPalette.INK : MenuPalette.DISABLED);
    }

    /** The slots one card covers: four columns on each of its two rows. */
    public static List<Integer> slotsOf(final int index) {
        final int firstColumn = index % 2 == 0 ? 0 : 5;
        final int upper = CARD_ROW[index / 2];
        final List<Integer> slots = new java.util.ArrayList<>(8);
        for (int row = upper; row <= upper + 1; row++) {
            for (int column = firstColumn; column < firstColumn + 4; column++) {
                slots.add(SlotGeometry.slot(column, row));
            }
        }
        return List.copyOf(slots);
    }

    /** Which card a slot belongs to, or -1 - the inverse of {@link #slotsOf}. */
    public static int cardOf(final int slot) {
        for (int index = 0; index < CARDS_PER_PAGE; index++) {
            if (slotsOf(index).contains(slot)) {
                return index;
            }
        }
        return -1;
    }
}
