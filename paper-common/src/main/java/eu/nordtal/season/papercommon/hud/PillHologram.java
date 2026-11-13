package eu.nordtal.season.papercommon.hud;

import eu.nordtal.season.messages.Palette;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.Pen;
import eu.nordtal.season.packrendering.ui.Pill;
import eu.nordtal.season.packrendering.ui.Symbol;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * A grey pill in the world that faces the player and shows faint through blocks, as a vanilla name does.
 * A text display is either hidden behind a block or drawn through it at one opacity, so the pill is four of them: its
 * panel and its ink, which blocks hide, and a faint see-through copy of each.
 */
public final class PillHologram {

    /** Wide enough that no station name wraps; the default 200 would. */
    private static final int LINE_WIDTH = 10000;

    private static final int FULL_BRIGHTNESS = 15;

    /** The opacity vanilla draws a name with through blocks, 0x20; a text display drops its text below 27. */
    public static final byte SEE_THROUGH_OPACITY = 32;

    /**
     * The opacity the ink shows with through blocks.
     * Dark ink as faint as the panel behind it could not be read, where vanilla's white name on black can.
     */
    public static final byte SEE_THROUGH_INK_OPACITY = (byte) 128;

    /**
     * How far the ink stands before the panel, in blocks toward the viewer.
     * A display that blocks hide tests its glyphs for depth, and ink drawn level with the panel flickers into it.
     */
    public static final float INK_LIFT = 0.01f;

    /** A pill as its displays draw it: the panel alone and the ink alone, at the same places. */
    public record Layers(Component panel, Component ink) {}

    private final TextDisplay panel;
    private final TextDisplay ink;
    private final TextDisplay faintPanel;
    private final TextDisplay faintInk;

    private PillHologram(
            final TextDisplay panel, final TextDisplay ink, final TextDisplay faintPanel, final TextDisplay faintInk) {
        this.panel = panel;
        this.ink = ink;
        this.faintPanel = faintPanel;
        this.faintInk = faintInk;
    }

    /** Returns the layers of the pill holding {@code symbol}, if one is given, and {@code runs} after it. */
    public static Layers layers(final @Nullable Symbol symbol, final List<Pill.Run> runs) {
        return new Layers(Pill.of(symbol, runs, Pen.Layer.ART), Pill.of(symbol, runs, Pen.Layer.INK));
    }

    /** Returns the layers of the pill holding {@code symbol} and {@code text} in ink. */
    public static Layers layers(final Symbol symbol, final String text) {
        return layers(symbol, List.of(new Pill.Run(text, Colours.INK)));
    }

    /**
     * Spawns the pill at {@code at}, never saved with the world.
     * {@code setup} runs on every display before it spawns, such as for a view range.
     */
    public static PillHologram spawn(final Location at, final Layers pill, final Consumer<TextDisplay> setup) {
        final World world = at.getWorld();
        final TextDisplay panel = world.spawn(at, TextDisplay.class, display -> style(display, pill.panel(), setup));
        final TextDisplay ink = world.spawn(at, TextDisplay.class, display -> {
            style(display, pill.ink(), setup);
            lift(display);
        });
        final TextDisplay faintPanel = world.spawn(at, TextDisplay.class, display -> {
            style(display, pill.panel(), setup);
            display.setSeeThrough(true);
            display.setTextOpacity(SEE_THROUGH_OPACITY);
        });
        final TextDisplay faintInk = world.spawn(at, TextDisplay.class, display -> {
            style(display, pill.ink(), setup);
            lift(display);
            display.setSeeThrough(true);
            display.setTextOpacity(SEE_THROUGH_INK_OPACITY);
        });
        return new PillHologram(panel, ink, faintPanel, faintInk);
    }

    private static void lift(final TextDisplay display) {
        final Transformation shown = display.getTransformation();
        // The billboard turns the display's z toward the viewer.
        display.setTransformation(new Transformation(
                new Vector3f(shown.getTranslation()).add(0, 0, INK_LIFT),
                shown.getLeftRotation(),
                shown.getScale(),
                shown.getRightRotation()));
    }

    // The same grey by day and by night.
    private static void style(final TextDisplay display, final Component text, final Consumer<TextDisplay> setup) {
        display.setPersistent(false);
        display.setSeeThrough(false);
        display.setBillboard(Display.Billboard.CENTER);
        display.setDefaultBackground(false);
        display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        display.setShadowed(false);
        display.setLineWidth(LINE_WIDTH);
        display.setBrightness(new Display.Brightness(FULL_BRIGHTNESS, FULL_BRIGHTNESS));
        display.text(text);
        setup.accept(display);
    }

    /** Draws {@code pill} in place of the one shown. */
    public void text(final Layers pill) {
        panel.text(pill.panel());
        ink.text(pill.ink());
        faintPanel.text(pill.panel());
        faintInk.text(pill.ink());
    }

    /** Whether every display is still in the world. */
    public boolean isValid() {
        return panel.isValid() && ink.isValid() && faintPanel.isValid() && faintInk.isValid();
    }

    public void remove() {
        entities().forEach(Entity::remove);
    }

    /** The displays, for an owner that removes its parts together. */
    public List<Entity> entities() {
        return List.of(panel, ink, faintPanel, faintInk);
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
