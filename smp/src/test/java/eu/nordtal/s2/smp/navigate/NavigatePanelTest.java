package eu.nordtal.s2.smp.navigate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.menu.MenuFont;
import eu.nordtal.s2.common.menu.MenuTitle;
import eu.nordtal.s2.common.menu.SlotGeometry;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walks {@code /navigate}'s composed window with the pack's own advances.
 *
 * <h2>Why walking it is the only honest check</h2>
 * The surface is a single string of code points in seven fonts, and every position in it is the
 * running sum of what came before. So an assertion that {@code NavigatePanel} <em>intends</em> to
 * put a pill at x 9 says nothing: what matters is where the cursor actually is when that glyph is
 * drawn, which depends on the panel's advance, on the eight shift glyphs, and on the width of every
 * character of every label before it. This class therefore rebuilds the cursor the way the client
 * does - from {@code gui.json} and {@link MenuFont}'s exported table - and can contradict the
 * renderer rather than restate it, which is the same thing {@code BoardFrameTest} does for the
 * boards and {@code MenuTitleTest} for the balloon.
 *
 * <p>What it cannot say is whether any of it looks right. Nothing here has been seen on a client;
 * that probe is the owner's and is on the checklist outside this repository.</p>
 */
class NavigatePanelTest {

    private static final Path ROOT = repositoryRoot();
    private static final String ASSETS = "resource-pack/src/assets";

    private static final List<NavigatePanel.Entry> THREE = List.of(
            new NavigatePanel.Entry(Glyphs.GUI_ROW_ICON_SPAWN, "This world's spawn", "12 m", false),
            new NavigatePanel.Entry(Glyphs.GUI_ROW_ICON_DEATH, "Where you last died", "1240 m", true),
            new NavigatePanel.Entry(Glyphs.GUI_ROW_ICON_POI, "Baeckerei am Fluss", "318 m", false));

    @Test
    @DisplayName("the whole surface returns the cursor to the title anchor")
    void theReadableTitleStillLandsWhereItWould() {
        final List<Run> runs = runs(surface(THREE, true, true));
        final Run last = runs.get(runs.size() - 1);
        assertEquals(MenuTitle.ANCHOR_X, last.x() + last.advance(),
                "the panel, five rows of furniture and the control row have to add up to nothing."
                        + " They do not merely move the readable title if they do not - every"
                        + " label after them is off by the same amount, and nothing fails");
    }

    @Test
    @DisplayName("every row's pill, icon and label land on the x the panel says")
    void everyRowIsWhereItSaysItIs() {
        final List<Run> runs = runs(surface(THREE, true, true));

        assertEquals(0, run(runs, Glyphs.FONT_GUI, Glyphs.GUI_PANEL_PLAIN_6).x(),
                "the panel has to start on the window's left edge");

        for (int row = 0; row < THREE.size(); row++) {
            final String font = Glyphs.FONT_GUI_ROWS[row];
            assertEquals(NavigatePanel.PILL_X, run(runs, font, Glyphs.GUI_ROW_PILL).x(),
                    "row " + row + "'s pill");
            assertEquals(NavigatePanel.PILL_X + 3, run(runs, font, THREE.get(row).icon()).x(),
                    "row " + row + "'s icon");

            final List<Run> text = textRuns(runs, font);
            assertEquals(2, text.size(), "row " + row + " should draw a name and a distance");
            assertEquals(MenuFont.fold(THREE.get(row).distance()), text.get(1).content(),
                    "the second thing written on a row is its distance");
            assertEquals(NavigatePanel.PILL_X + NavigatePanel.PILL_WIDTH - 3,
                    text.get(1).x() + text.get(1).advance(),
                    "row " + row + "'s distance is not flush with the pill's right edge");
            assertTrue(text.get(0).x() + text.get(0).advance() <= text.get(1).x(),
                    "row " + row + "'s name runs into its distance: '" + text.get(0).content()
                            + "' ends at " + (text.get(0).x() + text.get(0).advance())
                            + " and the distance starts at " + text.get(1).x());
        }
    }

    @Test
    @DisplayName("only the active entry wears the frame, and it is drawn after everything on its row")
    void theFrameMarksOneRowAndIsDrawnLast() {
        final List<Run> runs = runs(surface(THREE, true, true));
        final List<Run> frames = runs.stream()
                .filter(run -> run.content().equals(Glyphs.GUI_ROW_FRAME))
                .toList();

        assertEquals(1, frames.size(), "exactly one entry is the one being navigated to");
        assertEquals(Glyphs.FONT_GUI_ROWS[1], frames.get(0).font(), "the second entry is the active one");
        assertEquals(NavigatePanel.PILL_X, frames.get(0).x());

        final int frameAt = runs.indexOf(frames.get(0));
        final int lastOfRow = runs.stream().filter(run -> run.font().equals(Glyphs.FONT_GUI_ROWS[1]))
                .mapToInt(runs::indexOf).max().orElseThrow();
        assertEquals(lastOfRow, frameAt,
                "the frame is two pixels of white laid on the pill's own edge, so it has to be the"
                        + " last thing drawn on its row - a label composed after it would cross it");
    }

    @Test
    @DisplayName("a pill covers exactly the nine slot cells of its row, inset two")
    void aPillIsItsRow() {
        assertEquals(SlotGeometry.x(0) + NavigatePanel.INSET, NavigatePanel.PILL_X);
        assertEquals(SlotGeometry.x(8) + SlotGeometry.PITCH - 1 - NavigatePanel.INSET,
                NavigatePanel.PILL_X + NavigatePanel.PILL_WIDTH - 1,
                "the pill has to end two pixels inside the ninth slot cell, or a click at the far"
                        + " right of the row is a click on a slot with no paint over it");
    }

    @Test
    @DisplayName("every control's plate sits inside the slot cell that carries its click")
    void theControlsSitOnTheirSlots() {
        final List<Run> runs = runs(surface(THREE, true, true));
        final String font = Glyphs.FONT_GUI_ROWS[NavigatePanel.CONTROL_ROW];

        final Run stop = run(runs, font, Glyphs.GUI_ROW_BUTTON_WIDE);
        assertEquals(SlotGeometry.x(SlotGeometry.column(NavigatePanel.STOP_SLOTS.get(0)))
                + NavigatePanel.INSET, stop.x());
        assertTrue(stop.x() + stop.advance() - 1
                        <= SlotGeometry.x(SlotGeometry.column(NavigatePanel.STOP_SLOTS.get(2)))
                        + SlotGeometry.PITCH,
                "the stop plate runs past the last cell that carries its click, so part of it is"
                        + " painted over a slot that does nothing");

        for (final int slot : new int[] {NavigatePanel.PREV_SLOT, NavigatePanel.NEXT_SLOT}) {
            final int cell = SlotGeometry.x(SlotGeometry.column(slot));
            final Run plate = runs.stream()
                    .filter(run -> run.font().equals(font))
                    .filter(run -> run.content().equals(Glyphs.GUI_ROW_BUTTON_SMALL)
                            || run.content().equals(Glyphs.GUI_ROW_BUTTON_SMALL_OFF))
                    .filter(run -> run.x() == cell + NavigatePanel.INSET)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no page button on the cell of slot " + slot));
            assertTrue(plate.x() + plate.advance() - 1 < cell + SlotGeometry.PITCH,
                    "a page button has to stay inside its own cell");
        }
        assertEquals(NavigatePanel.CONTROL_ROW, SlotGeometry.row(NavigatePanel.PAGE_SLOT));
    }

    @Test
    @DisplayName("a page button with no page behind it is drawn greyed rather than left off")
    void aDeadPageButtonIsStillDrawn() {
        final List<Run> runs = runs(surface(THREE, false, false));
        final String font = Glyphs.FONT_GUI_ROWS[NavigatePanel.CONTROL_ROW];
        assertEquals(2, runs.stream().filter(run -> run.font().equals(font))
                        .filter(run -> run.content().equals(Glyphs.GUI_ROW_BUTTON_SMALL_OFF)).count(),
                "on a single-page list both page buttons are greyed and both are still there - a"
                        + " control that vanishes leaves a player wondering whether it was ever"
                        + " there, and the click on a greyed one is refused with a sound");
        assertEquals(0, runs.stream().filter(run -> run.content().equals(Glyphs.GUI_ROW_BUTTON_SMALL))
                .count());
    }

    @Test
    @DisplayName("every code point the surface uses is declared by the font that run names")
    void nothingIsDrawnOutOfAFontThatLacksIt() {
        final List<String> missing = new ArrayList<>();
        for (final Run run : runs(surface(THREE, true, true))) {
            final Set<Integer> declared = declared(run.font());
            run.whole().codePoints().forEach(codePoint -> {
                if (!declared.contains(codePoint)) {
                    missing.add("U+%X in %s".formatted(codePoint, run.font()));
                }
            });
        }
        assertEquals(List.of(), missing,
                "a code point a font does not declare reaches the player as the missing-glyph box,"
                        + " which is also six pixels wide - so the row is not merely ugly, every"
                        + " position after it is wrong");
    }

    @Test
    @DisplayName("more entries than a page holds is refused rather than drawn over the controls")
    void aPageIsFiveEntries() {
        final List<NavigatePanel.Entry> six = new ArrayList<>(THREE);
        six.addAll(THREE);
        assertThrows(IllegalArgumentException.class,
                () -> NavigatePanel.title(Component.empty(), six, "Stop", "1/1", false, false));
    }

    @Test
    @DisplayName("the four strings drawn inside the window carry no MiniMessage in either language")
    void theRowKeysAreTagless() {
        final List<String> tagged = new ArrayList<>();
        for (final String language : new String[] {"en", "de"}) {
            final Properties bundle = properties(
                    "smp/src/main/resources/messages/smp/" + language + ".properties");
            for (final String key : new String[] {"smp.navigate.stop-button", "smp.navigate.distance",
                    "smp.navigate.other-world", "smp.navigate.page"}) {
                final String value = bundle.getProperty(key);
                assertTrue(value != null, language + " does not declare " + key);
                if (value.indexOf('<') >= 0 || value.indexOf('>') >= 0) {
                    tagged.add(language + " " + key + " = " + value);
                }
            }
        }
        assertEquals(List.of(), tagged,
                "these four are drawn in the pack's five-pixel sheet by MenuFont, which folds them"
                        + " to capitals and prints them character for character. A MiniMessage tag"
                        + " in one of them is not parsed - it is printed, as <GRAY>, and the sheet"
                        + " has no angle brackets so it would come out as ?GRAY?");
    }

    @Test
    @DisplayName("the readable title is a sibling of the paint and names no font")
    void theTwoHalvesAreSeparate() {
        final Component title = NavigatePanel.title(Component.text("Navigate"), THREE, "Stop",
                "2/3", true, true);
        assertEquals(2, title.children().size());
        assertEquals(Glyphs.FONT_GUI, title.children().get(0).style().font().asString(),
                "the paint has to name nordtal:gui, or its code points resolve in whatever"
                        + " minecraft:default happens to hold at the same numbers");
        assertTrue(title.children().get(1).style().font() == null,
                "the readable title renders in minecraft:default, which is where the letters are -"
                        + " nordtal:gui carries no ascii sheet at all");
    }

    @Test
    @DisplayName("the pack draws each plate at the width the panel places it at")
    void theArtIsTheWidthTheJavaAssumes() {
        assertEquals(NavigatePanel.PILL_WIDTH, image("row_pill.png").getWidth());
        assertEquals(NavigatePanel.PILL_WIDTH, image("row_frame.png").getWidth());
        assertEquals(SlotGeometry.PITCH - 2 * NavigatePanel.INSET,
                image("row_button_small.png").getWidth());
        assertEquals(SlotGeometry.PITCH - 2 * NavigatePanel.INSET,
                image("row_button_small_off.png").getWidth());
        for (final String plate : new String[] {"row_pill.png", "row_frame.png",
                "row_button_wide.png", "row_button_small.png", "row_button_small_off.png"}) {
            assertEquals(SlotGeometry.PITCH - 2 * NavigatePanel.INSET, image(plate).getHeight(),
                    plate + " is not one slot row inset two, so it does not line up with the pill"
                            + " beside it");
        }
    }

    // --- walking the composition -----------------------------------------------------------

    /**
     * The painted half of the title, without the readable half.
     *
     * <p>The readable title is a sibling that names no font on purpose - it renders in
     * {@code minecraft:default}, whose advances are the client's and not ours, so it is the one
     * part of the composition this JVM cannot measure. {@link #theTwoHalvesAreSeparate} pins that
     * it is there and that it is separate; everything else here walks the half that is ours.</p>
     */
    private static Component surface(final List<NavigatePanel.Entry> entries,
                                     final boolean hasPrev, final boolean hasNext) {
        return NavigatePanel.title(Component.text("Navigate"), entries, "Stop", "2/3",
                hasPrev, hasNext).children().get(0);
    }

    /**
     * One drawn thing: the font it is drawn in, its payload, where its payload starts and how wide
     * it is. The leading shift glyphs are stripped off and turned into the {@code x}.
     */
    private record Run(String font, String content, int x, int advance, String whole) {
    }

    private static Run run(final List<Run> runs, final String font, final String content) {
        return runs.stream()
                .filter(run -> run.font().equals(font) && run.content().equals(content))
                .findFirst()
                .orElseThrow(() -> new AssertionError("nothing draws U+%X in %s"
                        .formatted(content.codePointAt(0), font)));
    }

    /** The readable runs of one row, in draw order - a name and, where there is one, a distance. */
    private static List<Run> textRuns(final List<Run> runs, final String font) {
        return runs.stream()
                .filter(run -> run.font().equals(font))
                // Readable text, not art: the row furniture is all in the private-use block, and
                // the sheet reaches up to U+2591 for the progress-bar characters.
                .filter(run -> !run.content().isEmpty())
                .filter(run -> run.content().codePointAt(0) < 0xFE000)
                .toList();
    }

    private static List<Run> runs(final Component surface) {
        final List<Run> out = new ArrayList<>();
        final int[] cursor = {MenuTitle.ANCHOR_X};
        walk(surface, null, out, cursor);
        return out;
    }

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

    private static final java.util.Map<Integer, Integer> GUI_ADVANCES = guiAdvances();

    private static java.util.Map<Integer, Integer> guiAdvances() {
        final java.util.Map<Integer, Integer> table = new java.util.HashMap<>();
        final JsonObject root = JsonParser
                .parseString(readText(ROOT.resolve(ASSETS + "/nordtal/font/gui.json")))
                .getAsJsonObject();
        for (final JsonElement element : root.getAsJsonArray("providers")) {
            final JsonObject provider = element.getAsJsonObject();
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

    /** Every code point one font resolves, straight out of its own file. */
    private static Set<Integer> declared(final String font) {
        final String file = Glyphs.FONT_GUI.equals(font)
                ? "gui.json"
                : "gui_r" + font.charAt(font.length() - 1) + ".json";
        final Set<Integer> out = new LinkedHashSet<>();
        final JsonObject root = JsonParser
                .parseString(readText(ROOT.resolve(ASSETS + "/nordtal/font/" + file)))
                .getAsJsonObject();
        for (final JsonElement element : root.getAsJsonArray("providers")) {
            final JsonObject provider = element.getAsJsonObject();
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

    // --- files ------------------------------------------------------------------------------

    private static BufferedImage image(final String name) {
        return read(ROOT.resolve(ASSETS + "/nordtal/textures/ui/gui/" + name));
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

    private static Properties properties(final String relative) {
        final Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(
                Files.newInputStream(ROOT.resolve(relative)), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + relative, e);
        }
        return properties;
    }

    private static Path repositoryRoot() {
        Path at = Path.of("").toAbsolutePath();
        while (at != null && !Files.isRegularFile(at.resolve("settings.gradle.kts"))) {
            at = at.getParent();
        }
        if (at == null) {
            throw new IllegalStateException("no settings.gradle.kts above " + Path.of("").toAbsolutePath());
        }
        return at;
    }
}
