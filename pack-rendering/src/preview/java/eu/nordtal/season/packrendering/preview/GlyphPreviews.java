package eu.nordtal.season.packrendering.preview;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.board.BoardFrame;
import eu.nordtal.season.packrendering.hud.BossBarLine;
import eu.nordtal.season.packrendering.hud.BossBarLine.Pill;
import eu.nordtal.season.packrendering.menu.MenuTitle;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.jspecify.annotations.Nullable;

/**
 * Renders one glyph in the menu panel, the HUD line and the board with the plugins' own code, as text-component JSON.
 * A place that the glyph's font never reaches is null. The README of this module documents the file.
 */
public final class GlyphPreviews {

    /** The row fonts, as glyphs.json names them. */
    public static final String FONT_GUI_ROW = "nordtal:gui_r{row}";

    /** The font of glyphs that a player's text carries, such as badges and flags. */
    public static final String FONT_DEFAULT = "minecraft:default";

    /**
     * Where a preview draws the glyph and what it writes beside it.
     *
     * @param x the window x of a row glyph or a surface
     */
    public record Layout(int rows, int row, int x, String text, int boardWidth) {}

    private GlyphPreviews() {}

    /** Returns the previews of {@code glyph} in {@code font}, whose drawn width is {@code width}. */
    public static JsonObject render(final String font, final String glyph, final int width, final Layout layout) {
        final Component text = Component.text(layout.text());
        @Nullable Component menu = null;
        @Nullable Component hud = null;
        @Nullable Component board = null;
        switch (font) {
            case Glyphs.FONT_GUI -> {
                final boolean panel = width == MenuTitle.PANEL_ADVANCE - 1;
                menu = panel
                        ? MenuTitle.on(glyph).build(text)
                        : MenuTitle.on(chestPanel(layout))
                                .overlay(glyph, layout.x(), width)
                                .build(text);
            }
            case FONT_GUI_ROW ->
                menu = MenuTitle.on(chestPanel(layout))
                        .rowArt(glyph, layout.row(), layout.x(), null)
                        .build(text);
            case Glyphs.FONT_BOSSBAR -> hud = BossBarLine.render(List.of(Pill.of(glyph, layout.text())));
            case Glyphs.FONT_BOARD -> board = BoardFrame.render(layout.boardWidth(), text, List.of());
            case FONT_DEFAULT -> {
                final Component line = Component.text(glyph + " " + layout.text());
                menu = MenuTitle.of(layout.rows(), line);
                board = BoardFrame.render(layout.boardWidth(), text, List.of(line));
            }
            default -> throw new IllegalArgumentException("no menu, HUD line or board draws the font " + font);
        }
        final JsonObject previews = new JsonObject();
        previews.add("menu", json(menu));
        previews.add("hud", json(hud));
        previews.add("board", json(board));
        return previews;
    }

    /**
     * Writes the previews of one glyph to {@code --out}, as {@code renderGlyphPreviews} calls it.
     * It takes {@code --font}, {@code --code} and {@code --width}, and the optional {@code --rows}, {@code --row},
     * {@code --x}, {@code --text} and {@code --board-width}.
     */
    public static void main(final String[] args) throws IOException {
        final Map<String, String> options = new HashMap<>();
        for (final String arg : args) {
            final int equals = arg.indexOf('=');
            if (!arg.startsWith("--") || equals < 0) {
                throw new IllegalArgumentException("an argument reads --name=value, not " + arg);
            }
            options.put(arg.substring(2, equals), arg.substring(equals + 1));
        }
        final String font = required(options, "font");
        final int codePoint = Integer.parseInt(required(options, "code"), 16);
        final Layout layout = new Layout(
                number(options, "rows", 3),
                number(options, "row", 0),
                number(options, "x", SlotGeometry.ORIGIN_X),
                options.getOrDefault("text", "Nordtal"),
                number(options, "board-width", BoardFrame.DEFAULT_WIDTH));

        final JsonObject out = new JsonObject();
        out.addProperty("font", font);
        out.addProperty("code", Integer.toHexString(codePoint).toUpperCase(Locale.ROOT));
        render(font, Character.toString(codePoint), Integer.parseInt(required(options, "width")), layout)
                .entrySet()
                .forEach(entry -> out.add(entry.getKey(), entry.getValue()));

        final Path file = Path.of(required(options, "out"));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(
                file,
                new GsonBuilder()
                                .setPrettyPrinting()
                                .disableHtmlEscaping()
                                .serializeNulls()
                                .create()
                                .toJson(out) + "\n",
                StandardCharsets.UTF_8);
        System.out.println(file.toAbsolutePath());
    }

    private static String chestPanel(final Layout layout) {
        if (layout.rows() < 1 || layout.rows() > MenuTitle.MAX_ROWS) {
            throw new IllegalArgumentException(
                    "a chest menu has 1 to " + MenuTitle.MAX_ROWS + " rows, not " + layout.rows());
        }
        return Glyphs.GUI_PANELS.get(layout.rows() - 1);
    }

    private static JsonElement json(final @Nullable Component component) {
        return component == null
                ? JsonNull.INSTANCE
                : GsonComponentSerializer.gson().serializeToTree(component);
    }

    private static String required(final Map<String, String> options, final String name) {
        final String value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException("--" + name + " is missing");
        }
        return value;
    }

    private static int number(final Map<String, String> options, final String name, final int fallback) {
        final String value = options.get(name);
        return value == null ? fallback : Integer.parseInt(value);
    }
}
