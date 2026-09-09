package eu.nordtal.s2.smp.npc;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.menu.MenuFont;
import eu.nordtal.s2.common.menu.MenuTitle;
import eu.nordtal.s2.common.menu.SlotGeometry;
import eu.nordtal.s2.smp.menu.PanelWalk;
import eu.nordtal.s2.smp.menu.PanelWalk.Run;

import net.kyori.adventure.text.Component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walks the deposit screen and holds it against the tray the pack drew.
 *
 * <p>The one that carries a rule rather than a measurement is
 * {@link #theTrayCoversEverySlotAPlayerCanFill}: this window's whole claim is that the area you
 * throw into is <em>one</em> surface, so a tray that stops one pixel short of a slot is a slot a
 * player can drop into that does not look like part of the tray - and neither the drop nor the
 * hand-in fails, so nothing anywhere says so.</p>
 */
class HandInPanelTest {

    @Test
    @DisplayName("the whole surface returns the cursor to the title anchor")
    void theReadableTitleStillLandsWhereItWould() {
        final List<Run> runs = PanelWalk.runs(surface("808 still needed", "Hand in"));
        assertEquals(MenuTitle.ANCHOR_X, runs.get(runs.size() - 1).end());
    }

    @Test
    @DisplayName("the tray covers every slot a player can fill, and no slot they cannot")
    void theTrayCoversEverySlotAPlayerCanFill() {
        final List<Run> runs = PanelWalk.runs(surface("808 still needed", "Hand in"));
        final Run tray = PanelWalk.find(runs, Glyphs.FONT_GUI, Glyphs.GUI_HANDIN_TRAY);
        final BufferedImage art = PanelWalk.image("handin_tray.png");

        assertEquals(SlotGeometry.x(0), tray.x(),
                "the tray starts at the first slot CELL's own corner, not inset - a tray inset two"
                        + " leaves a margin of panel around an area whose whole claim is that it is"
                        + " one surface");
        assertEquals(art.getWidth() + 1, tray.advance(), "the tray's advance is its width plus one");
        assertEquals(SlotGeometry.x(8) + SlotGeometry.PITCH, tray.x() + art.getWidth(),
                "the tray has to end on the ninth cell's own right edge");
        assertEquals(HandInPanel.DEPOSIT_ROWS * SlotGeometry.PITCH, art.getHeight(),
                "the tray is exactly the three rows a player may fill: one row taller and it runs"
                        + " under the confirm button, one shorter and the bottom row of the deposit"
                        + " area is bare panel");

        // And the slot map agrees with the picture, in both directions.
        for (int slot = 0; slot < HandInPanel.ROWS * SlotGeometry.COLUMNS; slot++) {
            assertEquals(SlotGeometry.row(slot) < HandInPanel.DEPOSIT_ROWS,
                    HandInPanel.isDeposit(slot), "slot " + slot);
        }
    }

    @Test
    @DisplayName("the confirm button sits on the cells that carry its click, and nowhere else")
    void theConfirmButtonIsItsSlots() {
        final List<Run> runs = PanelWalk.runs(surface("808 still needed", "Hand in"));
        final String font = Glyphs.FONT_GUI_ROWS[HandInPanel.FOOTER_ROW];
        final Run plate = PanelWalk.find(runs, font, Glyphs.GUI_ROW_BUTTON_CONFIRM);

        final int first = SlotGeometry.x(SlotGeometry.column(HandInPanel.CONFIRM_SLOTS.get(0)));
        final int last = SlotGeometry.x(SlotGeometry.column(HandInPanel.CONFIRM_SLOTS.get(2)));
        assertEquals(first + HandInPanel.INSET, plate.x());
        assertTrue(plate.end() - 1 <= last + SlotGeometry.PITCH,
                "the plate runs past the last cell that carries its click, so part of it is painted"
                        + " over a slot that does nothing");
        HandInPanel.CONFIRM_SLOTS.forEach(slot ->
                assertEquals(HandInPanel.FOOTER_ROW, SlotGeometry.row(slot)));
        assertEquals(3, Set.copyOf(HandInPanel.CONFIRM_SLOTS).size(), "three distinct cells");
    }

    @Test
    @DisplayName("the button's label is centred on its own plate")
    void theLabelIsCentred() {
        for (final String label : new String[] {"Hand in", "Abgeben", "OK"}) {
            final List<Run> runs = PanelWalk.runs(surface("1 still needed", label));
            final Run text = PanelWalk.textRuns(runs, Glyphs.FONT_GUI_ROWS[HandInPanel.FOOTER_ROW])
                    .stream()
                    .filter(run -> run.content().equals(MenuFont.fold(label)))
                    .findFirst().orElseThrow();

            final int leftGap = text.x() - HandInPanel.CONFIRM_X;
            final int rightGap = HandInPanel.CONFIRM_X + HandInPanel.CONFIRM_WIDTH - text.end();
            assertTrue(Math.abs(leftGap - rightGap) <= 1,
                    "'" + label + "' has " + leftGap + " pixels on the left and " + rightGap
                            + " on the right. A button's label is its whole content, so anything"
                            + " off-centre reads as a caption sitting beside it");
            assertTrue(leftGap >= 0 && rightGap >= 0, "'" + label + "' runs off its own plate");
        }
    }

    @Test
    @DisplayName("the still-needed line stops before the button rather than running under it")
    void theCountStaysOutOfTheButtonsWay() {
        final List<Run> runs = PanelWalk.runs(
                surface("999999999 still needed of oak, birch, spruce and every other log", "Hand in"));
        for (final Run run : PanelWalk.textRuns(runs, Glyphs.FONT_GUI_ROWS[HandInPanel.FOOTER_ROW])) {
            if (run.x() < HandInPanel.CONFIRM_X) {
                assertTrue(run.end() <= HandInPanel.CONFIRM_X,
                        "'" + run.content() + "' runs under the confirm button, where the end of it"
                                + " is simply invisible and nothing says so");
            }
        }
    }

    @Test
    @DisplayName("the sample sits in the footer row, apart from every slot that accepts an item")
    void theSampleIsNotADepositSlot() {
        assertEquals(HandInPanel.FOOTER_ROW, SlotGeometry.row(HandInPanel.SAMPLE_SLOT));
        assertEquals(0, SlotGeometry.column(HandInPanel.SAMPLE_SLOT));
        assertTrue(!HandInPanel.isDeposit(HandInPanel.SAMPLE_SLOT),
                "the sample is what is wanted, not something anybody may take or replace - it being"
                        + " a deposit slot would make it disappear into the next hand-in");
        assertTrue(!HandInPanel.CONFIRM_SLOTS.contains(HandInPanel.SAMPLE_SLOT));
    }

    @Test
    @DisplayName("every code point the surface uses is declared by the font that run names")
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
    @DisplayName("the two strings drawn inside the window carry no MiniMessage in either language")
    void theDrawnKeysAreTagless() {
        final List<String> tagged = new ArrayList<>();
        for (final String language : new String[] {"en", "de"}) {
            final Properties bundle = PanelWalk.bundle(language);
            for (final String key : new String[] {"smp.handin.still-needed",
                    "smp.handin.confirm-button"}) {
                final String value = bundle.getProperty(key);
                assertTrue(value != null, language + " does not declare " + key);
                if (value.indexOf('<') >= 0 || value.indexOf('>') >= 0) {
                    tagged.add(language + " " + key + " = " + value);
                }
            }
        }
        assertEquals(List.of(), tagged,
                "these two are drawn in the pack's five-pixel sheet by MenuFont, which prints them"
                        + " character for character - a tag would reach the player as literal text");
    }

    @Test
    @DisplayName("the confirm plate is not the refusing one, and the two are different pictures")
    void theAffirmingPlateIsItsOwn() {
        // The one button in these menus that does something that cannot be undone by clicking
        // again. /navigate's stop plate is the refusing red; this must not be the same picture, or
        // the only cue a player has for "this one commits" is the word on it.
        assertTrue(!Glyphs.GUI_ROW_BUTTON_CONFIRM.equals(Glyphs.GUI_ROW_BUTTON_WIDE));
        final BufferedImage confirm = PanelWalk.image("row_button_confirm.png");
        assertEquals(HandInPanel.CONFIRM_WIDTH, confirm.getWidth());
        assertEquals(SlotGeometry.PITCH - 2 * HandInPanel.INSET, confirm.getHeight());
        assertTrue(!samePixels(confirm, PanelWalk.image("row_button_small.png")),
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
        return PanelWalk.surface(
                HandInPanel.title(Component.text("Hand in"), needed, button));
    }
}
