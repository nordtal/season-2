package eu.nordtal.s2.smp.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.menu.MenuFont;
import eu.nordtal.s2.common.menu.MenuTitle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

/**
 * Rebuilds a composed menu surface the way the client lays it out, so a test can contradict a panel.
 *
 * It walks the cursor from {@code gui.json} and {@link MenuFont}'s table, shared by every panel's test.
 */
public final class PanelWalk {

    private static final Path ROOT = repositoryRoot();
    /** The assembled pack's assets, which the build names in {@code nordtal.pack}: the row fonts are generated. */
    private static final String ASSETS = packAssets();

    /** The advances of {@code nordtal:gui}, derived from that font's own file and its PNGs. */
    private static final Map<Integer, Integer> GUI_ADVANCES = guiAdvances();

    private PanelWalk() {}

    /** One drawn thing: its font, its payload, where the payload starts after leading shifts, and its width. */
    public record Run(String font, String content, int x, int advance, String whole) {

        /** Where this run's right edge lands. */
        public int end() {
            return x + advance;
        }
    }

    /** The painted half of a composed title, without the readable half, which this JVM cannot measure. */
    public static Component surface(final Component title) {
        return title.children().get(0);
    }

    /** Every run of a surface, in draw order, with the cursor resolved. */
    public static List<Run> runs(final Component surface) {
        final List<Run> out = new ArrayList<>();
        final int[] cursor = {MenuTitle.ANCHOR_X};
        walk(surface, null, out, cursor);
        return out;
    }

    /** The first run drawing exactly {@code content} in {@code font}. */
    public static Run find(final List<Run> runs, final String font, final String content) {
        return runs.stream()
                .filter(run -> run.font().equals(font) && run.content().equals(content))
                .findFirst()
                .orElseThrow(
                        () -> new AssertionError("nothing draws U+%X in %s".formatted(content.codePointAt(0), font)));
    }

    /** The readable runs of one row, in draw order. */
    public static List<Run> textRuns(final List<Run> runs, final String font) {
        return runs.stream()
                .filter(run -> run.font().equals(font))
                .filter(run -> !run.content().isEmpty())
                .filter(run -> run.content().codePointAt(0) < 0xFE000)
                .toList();
    }

    /** Every code point one font resolves, straight out of its own file. */
    public static Set<Integer> declared(final String font) {
        final String file =
                Glyphs.FONT_GUI.equals(font) ? "gui.json" : "gui_r" + font.charAt(font.length() - 1) + ".json";
        final Set<Integer> out = new LinkedHashSet<>();
        for (final JsonObject provider : providers(file)) {
            if ("space".equals(provider.get("type").getAsString())) {
                provider.getAsJsonObject("advances")
                        .keySet()
                        .forEach(key -> key.codePoints().forEach(out::add));
                continue;
            }
            provider.getAsJsonArray("chars")
                    .forEach(chars -> chars.getAsString().codePoints().forEach(out::add));
        }
        return out;
    }

    /** One of the pack's menu textures, read back. */
    public static BufferedImage image(final String name) {
        return read(ROOT.resolve(ASSETS + "/nordtal/textures/ui/gui/" + name));
    }

    /** One of {@code smp}'s message bundles, for asserting what a drawn string may contain. */
    public static Properties bundle(final String language) {
        final Properties properties = new Properties();
        final Path path = ROOT.resolve("smp/src/main/resources/messages/smp/" + language + ".properties");
        try (Reader reader = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
        return properties;
    }

    private static void walk(
            final Component component, final String inherited, final List<Run> out, final int[] cursor) {
        final String font =
                component.style().font() != null ? component.style().font().asString() : inherited;
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            final String whole = text.content();
            final StringBuilder payload = new StringBuilder();
            boolean leading = true;
            int start = cursor[0];
            for (final int codePoint : whole.codePoints().toArray()) {
                final int advance = advance(font, codePoint);
                if (leading && isShift(codePoint)) {
                    cursor[0] += advance;
                    start = cursor[0];
                    continue;
                }
                leading = false;
                payload.appendCodePoint(codePoint);
                cursor[0] += advance;
            }
            out.add(new Run(font, payload.toString(), start, cursor[0] - start, whole));
        }
        for (final Component child : component.children()) {
            walk(child, font, out, cursor);
        }
    }

    private static boolean isShift(final int codePoint) {
        return (codePoint >= 0xFF001 && codePoint <= 0xFF128) || (codePoint >= 0xFF801 && codePoint <= 0xFF928);
    }

    /** The advance of one code point in one font, taken from the pack and never from the code. */
    private static int advance(final String font, final int codePoint) {
        if (Glyphs.FONT_GUI.equals(font)) {
            return GUI_ADVANCES.getOrDefault(codePoint, 0);
        }
        return MenuFont.advance(codePoint);
    }

    private static Map<Integer, Integer> guiAdvances() {
        final Map<Integer, Integer> table = new HashMap<>();
        for (final JsonObject provider : providers("gui.json")) {
            if ("space".equals(provider.get("type").getAsString())) {
                provider.getAsJsonObject("advances")
                        .entrySet()
                        .forEach(entry -> entry.getKey()
                                .codePoints()
                                .forEach(codePoint ->
                                        table.put(codePoint, entry.getValue().getAsInt())));
                continue;
            }
            final BufferedImage png = read(ROOT.resolve(ASSETS + "/nordtal/textures")
                    .resolve(provider.get("file").getAsString().replace("nordtal:", "")));
            table.put(provider.getAsJsonArray("chars").get(0).getAsString().codePointAt(0), png.getWidth() + 1);
        }
        return table;
    }

    private static List<JsonObject> providers(final String file) {
        final List<JsonObject> out = new ArrayList<>();
        final JsonObject root = JsonParser.parseString(readText(ROOT.resolve(ASSETS + "/nordtal/font/" + file)))
                .getAsJsonObject();
        for (final JsonElement element : root.getAsJsonArray("providers")) {
            out.add(element.getAsJsonObject());
        }
        return out;
    }

    private static BufferedImage read(final Path path) {
        try {
            final BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) {
                throw new IllegalStateException(path + " is not an image ImageIO can read");
            }
            return image;
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    private static String readText(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    private static String packAssets() {
        final String pack = System.getProperty("nordtal.pack");
        if (pack == null) {
            throw new IllegalStateException("nordtal.pack is not set: smp/build.gradle.kts needs"
                    + " resourcePack(project(\":resource-pack\", \"pack\"))");
        }
        return Path.of(pack).resolve("assets").toString();
    }

    private static Path repositoryRoot() {
        Path at = Path.of("").toAbsolutePath();
        while (at != null && !Files.isRegularFile(at.resolve("settings.gradle.kts"))) {
            at = at.getParent();
        }
        if (at == null) {
            throw new IllegalStateException(
                    "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
        }
        return at;
    }
}
