package eu.nordtal.s2.smp.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.menu.MenuFont;
import eu.nordtal.s2.common.menu.MenuTitle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

import javax.imageio.ImageIO;
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

/**
 * Rebuilds a composed menu surface the way the client lays it out, so a test can contradict a panel
 * rather than restate it.
 *
 * <h2>Why walking it is the only honest check</h2>
 * A surface is a single string of code points in several fonts, and every position in it is the
 * running sum of what came before. So an assertion that a panel <em>intends</em> to put a plate at
 * x 9 says nothing: what matters is where the cursor actually is when that glyph is drawn, which
 * depends on the panel's advance, on the shift glyphs, and on the width of every character of every
 * label before it. This walks the cursor from {@code gui.json} and {@link MenuFont}'s exported
 * table, which is the same thing {@code BoardFrameTest} does for the boards and {@code MenuTitleTest}
 * for the balloon.
 *
 * <p>It lives apart from any one panel's test because there are four of them now and a second copy
 * of a cursor walker is a second answer about one layout - which is the failure this whole
 * arrangement exists to prevent.</p>
 *
 * <p>What none of it can say is whether any of it looks right. Nothing here has been seen on a
 * client; that probe is the owner's and is on the checklist outside this repository.</p>
 */
public final class PanelWalk {

    private static final Path ROOT = repositoryRoot();
    private static final String ASSETS = "resource-pack/src/assets";

    /** The advances of {@code nordtal:gui}, derived from that font's own file and its PNGs. */
    private static final Map<Integer, Integer> GUI_ADVANCES = guiAdvances();

    private PanelWalk() {
    }

    /**
     * One drawn thing: the font it is in, its payload, where the payload starts and how wide it is.
     *
     * <p>The leading shift glyphs are stripped off and turned into the {@code x}, which is what
     * makes an assertion about a position readable at all.</p>
     */
    public record Run(String font, String content, int x, int advance, String whole) {

        /** Where this run's right edge lands. */
        public int end() {
            return x + advance;
        }
    }

    /**
     * The painted half of a composed title, without the readable half.
     *
     * <p>The readable title is a sibling that names no font on purpose - it renders in
     * {@code minecraft:default}, whose advances are the client's and not ours, so it is the one part
     * of the composition this JVM cannot measure.</p>
     */
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
                .orElseThrow(() -> new AssertionError("nothing draws U+%X in %s"
                        .formatted(content.codePointAt(0), font)));
    }

    /**
     * The readable runs of one row, in draw order.
     *
     * <p>Readable text and not art: every piece of row furniture is in the private-use block, and
     * the five-pixel sheet reaches up to {@code U+2591} for the progress-bar characters.</p>
     */
    public static List<Run> textRuns(final List<Run> runs, final String font) {
        return runs.stream()
                .filter(run -> run.font().equals(font))
                .filter(run -> !run.content().isEmpty())
                .filter(run -> run.content().codePointAt(0) < 0xFE000)
                .toList();
    }

    /** Every code point one font resolves, straight out of its own file. */
    public static Set<Integer> declared(final String font) {
        final String file = Glyphs.FONT_GUI.equals(font)
                ? "gui.json"
                : "gui_r" + font.charAt(font.length() - 1) + ".json";
        final Set<Integer> out = new LinkedHashSet<>();
        for (final JsonObject provider : providers(file)) {
            if ("space".equals(provider.get("type").getAsString())) {
                provider.getAsJsonObject("advances").keySet().forEach(key ->
                        key.codePoints().forEach(out::add));
                continue;
            }
            provider.getAsJsonArray("chars").forEach(chars ->
                    chars.getAsString().codePoints().forEach(out::add));
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
        final Path path = ROOT.resolve("smp/src/main/resources/messages/smp/"
                + language + ".properties");
        try (Reader reader = new InputStreamReader(Files.newInputStream(path),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
        return properties;
    }

    // --- walking ------------------------------------------------------------------------

    private static void walk(final Component component, final String inherited,
                             final List<Run> out, final int[] cursor) {
        final String font = component.style().font() != null
                ? component.style().font().asString() : inherited;
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
        return (codePoint >= 0xFF001 && codePoint <= 0xFF128)
                || (codePoint >= 0xFF801 && codePoint <= 0xFF928);
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
                provider.getAsJsonObject("advances").entrySet().forEach(entry ->
                        entry.getKey().codePoints().forEach(codePoint ->
                                table.put(codePoint, entry.getValue().getAsInt())));
                continue;
            }
            final BufferedImage png = read(ROOT.resolve(ASSETS + "/nordtal/textures")
                    .resolve(provider.get("file").getAsString().replace("nordtal:", "")));
            table.put(provider.getAsJsonArray("chars").get(0).getAsString().codePointAt(0),
                    png.getWidth() + 1);
        }
        return table;
    }

    private static List<JsonObject> providers(final String file) {
        final List<JsonObject> out = new ArrayList<>();
        final JsonObject root = JsonParser
                .parseString(readText(ROOT.resolve(ASSETS + "/nordtal/font/" + file)))
                .getAsJsonObject();
        for (final JsonElement element : root.getAsJsonArray("providers")) {
            out.add(element.getAsJsonObject());
        }
        return out;
    }

    // --- files --------------------------------------------------------------------------

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

    private static Path repositoryRoot() {
        Path at = Path.of("").toAbsolutePath();
        while (at != null && !Files.isRegularFile(at.resolve("settings.gradle.kts"))) {
            at = at.getParent();
        }
        if (at == null) {
            throw new IllegalStateException("no settings.gradle.kts above "
                    + Path.of("").toAbsolutePath());
        }
        return at;
    }
}
