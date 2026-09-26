package eu.nordtal.s2.smp.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.menu.MenuFont;
import eu.nordtal.s2.common.menu.MenuTitle;
import eu.nordtal.s2.common.menu.SlotGeometry;
import eu.nordtal.s2.smp.menu.PanelWalk;
import eu.nordtal.s2.smp.menu.PanelWalk.Run;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/**
 * Walks the deposit screen and holds it against the tray the pack drew.
 *
 * The one that carries a rule rather than a measurement is {@link #theTrayCoversEverySlotAPlayerCanFill}: this
 * window's whole claim is that the area you throw into is <em>one</em> surface, so a tray that stops one pixel short
 * of a slot is a slot a player can drop into that does not look like part of the tray - and neither the drop nor the
 * hand-in fails, so nothing anywhere says so.
 */
class HandInPanelTest {

    @Test
    void theReadableTitleStillLandsWhereItWould() {
        final List<Run> runs = PanelWalk.runs(surface("808 still needed", "Hand in"));
        assertEquals(MenuTitle.ANCHOR_X, runs.get(runs.size() - 1).end());
    }

    @Test
    void theTrayCoversEverySlotAPlayerCanFill() {
        final List<Run> runs = PanelWalk.runs(surface("808 still needed", "Hand in"));
        final Run tray = PanelWalk.find(runs, Glyphs.FONT_GUI, Glyphs.GUI_HANDIN_TRAY);
        final BufferedImage art = PanelWalk.image("handin_tray.png");

        assertEquals(
                SlotGeometry.x(0),
                tray.x(),
                "the tray starts at the first slot CELL's own corner, not inset - a tray inset two"
                        + " leaves a margin of panel around an area whose whole claim is that it is"
                        + " one surface");
        assertEquals(art.getWidth() + 1, tray.advance(), "the tray's advance is its width plus one");
        assertEquals(
                SlotGeometry.x(8) + SlotGeometry.PITCH,
                tray.x() + art.getWidth(),
                "the tray has to end on the ninth cell's own right edge");
        assertEquals(
                HandInPanel.DEPOSIT_ROWS * SlotGeometry.PITCH,
                art.getHeight(),
                "the tray is exactly the three rows a player may fill: one row taller and it runs"
                        + " under the confirm button, one shorter and the bottom row of the deposit"
                        + " area is bare panel");

        // And the slot map agrees with the picture, in both directions.
        for (int slot = 0; slot < HandInPanel.ROWS * SlotGeometry.COLUMNS; slot++) {
            assertEquals(
                    SlotGeometry.row(slot) < HandInPanel.DEPOSIT_ROWS, HandInPanel.isDeposit(slot), "slot " + slot);
        }
    }

    @Test
    void theConfirmButtonIsItsSlots() {
        final List<Run> runs = PanelWalk.runs(surface("808 still needed", "Hand in"));
        final String font = Glyphs.FONT_GUI_ROWS.get(HandInPanel.FOOTER_ROW);
        final Run plate = PanelWalk.find(runs, font, Glyphs.GUI_ROW_BUTTON_CONFIRM);

        final int first = SlotGeometry.x(SlotGeometry.column(HandInPanel.CONFIRM_SLOTS.get(0)));
        final int last = SlotGeometry.x(SlotGeometry.column(HandInPanel.CONFIRM_SLOTS.get(2)));
        assertEquals(first + HandInPanel.INSET, plate.x());
        assertTrue(
                plate.end() - 1 <= last + SlotGeometry.PITCH,
                "the plate runs past the last cell that carries its click, so part of it is painted"
                        + " over a slot that does nothing");
        HandInPanel.CONFIRM_SLOTS.forEach(slot -> assertEquals(HandInPanel.FOOTER_ROW, SlotGeometry.row(slot)));
        assertEquals(3, Set.copyOf(HandInPanel.CONFIRM_SLOTS).size(), "three distinct cells");
    }

    @Test
    void theLabelIsCentred() {
        for (final String label : new String[] {"Hand in", "Abgeben", "OK"}) {
            final List<Run> runs = PanelWalk.runs(surface("1 still needed", label));
            final Run text = PanelWalk.textRuns(runs, Glyphs.FONT_GUI_ROWS.get(HandInPanel.FOOTER_ROW)).stream()
                    .filter(run -> run.content().equals(MenuFont.fold(label)))
                    .findFirst()
                    .orElseThrow();

            final int leftGap = text.x() - HandInPanel.CONFIRM_X;
            final int rightGap = HandInPanel.CONFIRM_X + HandInPanel.CONFIRM_WIDTH - text.end();
            assertTrue(
                    Math.abs(leftGap - rightGap) <= 1,
                    "'" + label + "' has " + leftGap + " pixels on the left and " + rightGap
                            + " on the right. A button's label is its whole content, so anything"
                            + " off-centre reads as a caption sitting beside it");
            assertTrue(leftGap >= 0 && rightGap >= 0, "'" + label + "' runs off its own plate");
        }
    }

    @Test
    void theCountStaysOutOfTheButtonsWay() {
        final List<Run> runs =
                PanelWalk.runs(surface("999999999 still needed of oak, birch, spruce and every other log", "Hand in"));
        for (final Run run : PanelWalk.textRuns(runs, Glyphs.FONT_GUI_ROWS.get(HandInPanel.FOOTER_ROW))) {
            if (run.x() < HandInPanel.CONFIRM_X) {
                assertTrue(
                        run.end() <= HandInPanel.CONFIRM_X,
                        "'" + run.content() + "' runs under the confirm button, where the end of it"
                                + " is simply invisible and nothing says so");
            }
        }
    }

    @Test
    void thetwoFootersLineUp() {
        // These two windows are the same footer with a different sentence; nothing else compares the two directly.
        final Run needed = PanelWalk.textRuns(
                        PanelWalk.runs(surface("808 still needed", "Hand in")),
                        Glyphs.FONT_GUI_ROWS.get(HandInPanel.FOOTER_ROW))
                .get(0);
        assertEquals(SlotGeometry.x(1) + HandInPanel.INSET, needed.x());
        assertEquals(
                eu.nordtal.s2.smp.grave.GravePanel.INSET,
                HandInPanel.INSET,
                "the two footers are inset by different amounts, so their plates do not line up" + " either");
    }

    @Test
    void theSampleIsNotADepositSlot() {
        assertEquals(HandInPanel.FOOTER_ROW, SlotGeometry.row(HandInPanel.SAMPLE_SLOT));
        assertEquals(0, SlotGeometry.column(HandInPanel.SAMPLE_SLOT));
        assertTrue(
                !HandInPanel.isDeposit(HandInPanel.SAMPLE_SLOT),
                "the sample is what is wanted, not something anybody may take or replace - it being"
                        + " a deposit slot would make it disappear into the next hand-in");
        assertTrue(!HandInPanel.CONFIRM_SLOTS.contains(HandInPanel.SAMPLE_SLOT));
    }

    @Test
    void nothingIsDrawnOutOfAFontThatLacksIt() {
        final List<String> missing = new ArrayList<>();
        for (final Run run : PanelWalk.runs(surface("808 still needed", "Hand in"))) {
            final Set<Integer> declared = PanelWalk.declared(run.font());
            run.whole().codePoints().forEach(codePoint -> {
                if (!declared.contains(codePoint)) {
                    missing.add("U+%X in %s".formatted(codePoint, run.font()));
                }
            });
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void theDrawnKeysAreTagless() {
        final List<String> tagged = new ArrayList<>();
        for (final String language : new String[] {"en", "de"}) {
            final Properties bundle = PanelWalk.bundle(language);
            for (final String key : new String[] {"smp.handin.still-needed", "smp.handin.confirm-button"}) {
                final String value = bundle.getProperty(key);
                assertTrue(value != null, language + " does not declare " + key);
                if (value.indexOf('<') >= 0 || value.indexOf('>') >= 0) {
                    tagged.add(language + " " + key + " = " + value);
                }
            }
        }
        assertEquals(
                List.of(),
                tagged,
                "these two are drawn in the pack's five-pixel sheet by MenuFont, which prints them"
                        + " character for character - a tag would reach the player as literal text");
    }

    @Test
    void theAffirmingPlateIsItsOwn() {
        // The one button here that cannot be undone must not share /navigate's stop-plate picture for that reason.
        assertTrue(!Glyphs.GUI_ROW_BUTTON_CONFIRM.equals(Glyphs.GUI_ROW_BUTTON_WIDE));
        final BufferedImage confirm = PanelWalk.image("row_button_confirm.png");
        assertEquals(HandInPanel.CONFIRM_WIDTH, confirm.getWidth());
        assertEquals(SlotGeometry.PITCH - 2 * HandInPanel.INSET, confirm.getHeight());
        assertTrue(
                !samePixels(confirm, PanelWalk.image("row_button_small.png")),
                "the affirming plate and a neutral one are the same art");
    }

    private static boolean samePixels(final BufferedImage one, final BufferedImage other) {
        if (one.getWidth() != other.getWidth() || one.getHeight() != other.getHeight()) {
            return false;
        }
        for (int y = 0; y < one.getHeight(); y++) {
            for (int x = 0; x < one.getWidth(); x++) {
                if (one.getRGB(x, y) != other.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Component surface(final String needed, final String button) {
        return PanelWalk.surface(HandInPanel.title(Component.text("Hand in"), needed, button));
    }
}
