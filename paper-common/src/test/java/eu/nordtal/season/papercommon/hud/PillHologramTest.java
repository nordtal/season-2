package eu.nordtal.season.papercommon.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.messages.Palette;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.Pill;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

class PillHologramTest {

    private static final TextColor MUTED = TextColor.fromHexString(Palette.DEFAULTS.hex(Tone.MUTED));

    @Test
    void theMutedToneIsSoftAndTheRestInk() {
        final Component text =
                Component.text("Verschwindet in ", MUTED).append(Component.text("4 min", TextColor.color(0xFFFFFF)));
        assertEquals(
                List.of(new Pill.Run("Verschwindet in ", Colours.SOFT), new Pill.Run("4 min", Colours.INK)),
                PillHologram.runs(text, Palette.DEFAULTS));
    }

    @Test
    void aChildTakesItsParentsToneAndALineBreakBecomesASpace() {
        final Component text = Component.text()
                .color(MUTED)
                .append(Component.text("noch\n5 s"))
                .build();
        assertEquals(List.of(new Pill.Run("noch 5 s", Colours.SOFT)), PillHologram.runs(text, Palette.DEFAULTS));
    }
}
