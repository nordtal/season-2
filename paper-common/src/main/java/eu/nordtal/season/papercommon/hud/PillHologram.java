package eu.nordtal.season.papercommon.hud;

import eu.nordtal.season.messages.Palette;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.Pill;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.jspecify.annotations.Nullable;

/** A text display that draws a grey pill in the world, facing the player and seen through blocks. */
public final class PillHologram {

    /** Wide enough that no station name wraps; the default 200 would. */
    private static final int LINE_WIDTH = 10000;

    private static final int FULL_BRIGHTNESS = 15;

    private PillHologram() {}

    /**
     * Sets {@code display} up for a pill.
     * Seen through, it draws its letters after the pill without a depth test, so they never z-fight with the pill,
     * and it looks the same behind a wall, by day and by night.
     */
    public static void style(final TextDisplay display) {
        display.setSeeThrough(true);
        display.setBillboard(Display.Billboard.CENTER);
        display.setDefaultBackground(false);
        display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        display.setShadowed(false);
        display.setLineWidth(LINE_WIDTH);
        display.setBrightness(new Display.Brightness(FULL_BRIGHTNESS, FULL_BRIGHTNESS));
    }

    /** Returns the text of {@code formatted} as a pill's runs: the muted tone of {@code palette} soft, the rest ink. */
    public static List<Pill.Run> runs(final Component formatted, final Palette palette) {
        final TextColor muted = TextColor.fromHexString(palette.hex(Tone.MUTED));
        final List<Pill.Run> runs = new ArrayList<>();
        collect(formatted, null, muted, runs);
        return runs;
    }

    private static void collect(
            final Component component,
            final @Nullable TextColor inherited,
            final @Nullable TextColor muted,
            final List<Pill.Run> runs) {
        final TextColor colour = component.color() == null ? inherited : component.color();
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            final TextColor drawn = colour != null && colour.equals(muted) ? Colours.SOFT : Colours.INK;
            runs.add(new Pill.Run(text.content().replace('\n', ' '), drawn));
        }
        component.children().forEach(child -> collect(child, colour, muted, runs));
    }
}
