package eu.nordtal.season.smp.duel;

import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.packrendering.ui.BodyLine;
import eu.nordtal.season.packrendering.ui.ChestTitle;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.Letters;
import eu.nordtal.season.packrendering.ui.Look;
import eu.nordtal.season.packrendering.ui.Mark;
import eu.nordtal.season.packrendering.ui.Symbol;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

/**
 * Draws the five-row stake chest: the own side on the left, the opponent's mirrored on the right.
 *
 * Rows hold the heads, two rows of deposits, the aura figures with the own stepper, and the ready plate.
 */
final class StakePanel {

    static final int ROWS = 5;

    static final int OWN_HEAD = SlotGeometry.slot(0, 0);
    static final int THEIR_HEAD = SlotGeometry.slot(8, 0);

    /** The own deposit area: columns 0 to 3 of rows 1 and 2. */
    static final List<Integer> OWN_DEPOSIT = area(0);

    /** The opponent's deposit area, mirrored: columns 5 to 8 of rows 1 and 2. */
    static final List<Integer> THEIR_DEPOSIT = area(5);

    private static final Set<Integer> OWN_SET = Set.copyOf(OWN_DEPOSIT);

    private static final int FIGURE_ROW = 3;
    private static final int READY_ROW = 4;

    static final int LOWER = SlotGeometry.slot(0, FIGURE_ROW);
    static final int RAISE = SlotGeometry.slot(3, FIGURE_ROW);
    static final List<Integer> OWN_FIGURE = List.of(SlotGeometry.slot(1, FIGURE_ROW), SlotGeometry.slot(2, FIGURE_ROW));
    static final List<Integer> THEIR_FIGURE = IntStream.rangeClosed(5, 8)
            .mapToObj(column -> SlotGeometry.slot(column, FIGURE_ROW))
            .toList();

    /** The ready plate over columns 0 to 3; while both are ready, columns 0 to 2 count down. */
    static final List<Integer> READY = IntStream.rangeClosed(0, 3)
            .mapToObj(column -> SlotGeometry.slot(column, READY_ROW))
            .toList();

    static final List<Integer> COUNTDOWN = READY.subList(0, 3);
    static final int WITHDRAW = SlotGeometry.slot(3, READY_ROW);

    /** The opponent's status: their head once they confirmed, with a tick beside it. */
    static final int THEIR_STATUS = SlotGeometry.slot(5, READY_ROW);

    private static final int NAME_X = ChestTitle.plateX(1) + 1;
    private static final int NAME_ROOM = ChestTitle.plateWidth(3);
    private static final int TICK_X = ChestTitle.plateX(6) + 1;

    /**
     * What one player's chest shows.
     *
     * @param their     the opponent's name, null while the player stands alone
     * @param seconds   the seconds of the countdown to the lock, 0 while none runs
     * @param refusal   why the last confirmation was refused, or null
     */
    record View(
            String title,
            String own,
            @Nullable String their,
            int offer,
            int cap,
            int theirOffer,
            boolean ready,
            boolean theirReady,
            int seconds,
            @Nullable String refusal,
            Labels labels) {}

    /** The words drawn into the title. */
    record Labels(String ready, String alone, String countdown) {}

    private StakePanel() {}

    static boolean isOwnDeposit(final int slot) {
        return OWN_SET.contains(slot);
    }

    static Component title(final View view) {
        final List<Mark> marks = new ArrayList<>();
        if (view.refusal() != null) {
            marks.add(Mark.text(view.refusal(), Colours.REFUSAL));
        }
        final ChestTitle title = ChestTitle.of(ROWS).boldHeader(Symbol.SHIELD, view.title(), marks);
        title.text(NAME_X, 0, Letters.cut(view.own(), NAME_ROOM), Colours.INK);
        OWN_DEPOSIT.forEach(slot -> title.depositSlot(SlotGeometry.column(slot), SlotGeometry.row(slot)));
        final @Nullable String their = view.their();
        if (their == null) {
            title.centred(5, 1, 4, view.labels().alone(), Colours.FAINT);
        } else {
            final String name = Letters.cut(their, NAME_ROOM);
            title.text(ChestTitle.plateX(8) - 3 - Letters.visibleWidth(name), 0, name, Colours.INK);
            THEIR_DEPOSIT.forEach(slot -> title.depositSlot(SlotGeometry.column(slot), SlotGeometry.row(slot)));
            figure(title, 5, 4, view.theirOffer());
            if (view.theirReady()) {
                title.symbol(TICK_X, READY_ROW, Symbol.TICK, Colours.ACCENT);
            }
        }
        stepper(title, 0, "-", view.offer() > 0);
        stepper(title, 3, "+", view.offer() < view.cap());
        figure(title, 1, 2, view.offer());
        if (view.seconds() > 0) {
            title.plate(0, READY_ROW, 3, Look.PRIMARY)
                    .label(0, READY_ROW, 3, null, view.labels().countdown(), Look.PRIMARY)
                    .plate(3, READY_ROW, 1, Look.NORMAL)
                    .symbol(ChestTitle.plateX(3) + 4, READY_ROW, Symbol.RETURN, Look.NORMAL.ink());
        } else {
            final Look look = view.ready() ? Look.PRIMARY : Look.NORMAL;
            title.plate(0, READY_ROW, 4, look)
                    .label(
                            0,
                            READY_ROW,
                            4,
                            view.ready() ? Symbol.TICK : null,
                            view.labels().ready(),
                            look);
        }
        return title.build(BodyLine.silent(view.title() + " " + view.offer()));
    }

    private static void stepper(final ChestTitle title, final int column, final String sign, final boolean enabled) {
        final Look look = enabled ? Look.NORMAL : Look.DISABLED;
        title.plate(column, FIGURE_ROW, 1, look).label(column, FIGURE_ROW, 1, null, sign, look);
    }

    /** Draws the aura symbol and {@code amount} centred over {@code cells} cells from {@code column}. */
    private static void figure(final ChestTitle title, final int column, final int cells, final int amount) {
        final String figure = Integer.toString(amount);
        final int inked = Symbol.BEFORE_TEXT + Letters.visibleWidth(figure);
        final int x = ChestTitle.plateX(column) + (ChestTitle.plateWidth(cells) - inked + 1) / 2;
        final TextColor colour = amount > 0 ? Colours.INK : Colours.FAINT;
        title.symbol(x, FIGURE_ROW, Symbol.XP, colour).text(x + Symbol.BEFORE_TEXT, FIGURE_ROW, figure, colour);
    }

    private static List<Integer> area(final int firstColumn) {
        final List<Integer> slots = new ArrayList<>();
        for (int row = 1; row <= 2; row++) {
            for (int column = firstColumn; column < firstColumn + 4; column++) {
                slots.add(SlotGeometry.slot(column, row));
            }
        }
        return List.copyOf(slots);
    }
}
