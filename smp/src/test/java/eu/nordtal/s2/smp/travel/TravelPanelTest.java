package eu.nordtal.s2.smp.travel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.menu.MenuTitle;
import eu.nordtal.s2.smp.milestone.Unlock;
import eu.nordtal.s2.smp.world.WorldRole;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds {@link TravelPanel}'s geometry against the panel the pack actually draws.
 *
 * <p>Two things decide where a card is: {@code resource-pack/tools/generate_gui_panels.py}, which
 * paints it, and {@link TravelPanel}, which tells {@link MenuTitle.Canvas} where to lay an overlay
 * and {@link BalloonMenu} which slots to fill. Nothing else compares them. So this reads the panel
 * PNG, finds each card by the colour it is painted in, and asserts its rectangle is the one the
 * Java side computes - and reads {@code gui.json} to assert each overlay's ascent lands it on the
 * card row it is named for and its advance is the card's width plus one.</p>
 */
class TravelPanelTest {

    private static final Path ROOT = repositoryRoot();
    private static final Path ASSETS = ROOT.resolve("resource-pack/src/assets/nordtal");

    /**
     * The four cards' colours - fill, outline, highlight - verbatim from
     * generate_gui_panels.py's TILES. A card is the bounding box of every pixel in any of its
     * three exact colours; the pictogram is the highlight colour blended at partial alpha and
     * therefore never exact, so it cannot widen the box.
     */
    private static final Map<WorldRole, List<Integer>> COLOURS = Map.of(
            WorldRole.NORDTAL, List.of(rgb(82, 168, 84), rgb(44, 108, 48), rgb(140, 210, 136)),
            WorldRole.FARM, List.of(rgb(236, 168, 56), rgb(170, 110, 24), rgb(250, 214, 130)),
            WorldRole.NETHER, List.of(rgb(206, 66, 58), rgb(136, 34, 30), rgb(242, 140, 120)),
            WorldRole.END, List.of(rgb(128, 82, 190), rgb(78, 44, 130), rgb(190, 150, 232)));

    @Test
    @DisplayName("every card is painted exactly where TravelPanel says it is")
    void theCardsAreWhereTheJavaSaysTheyAre() {
        final BufferedImage panel = read(ASSETS.resolve("textures/ui/gui/travel.png"));
        for (final BalloonMenu.Entry entry : BalloonMenu.of(WorldRole.NORDTAL, EnumSet.noneOf(Unlock.class))) {
            final int[] box = boundingBox(panel, COLOURS.get(entry.destination()));
            assertEquals(TravelPanel.x(entry.column()), box[0], entry.destination() + "'s left edge");
            assertEquals(TravelPanel.y(entry.row()), box[1], entry.destination() + "'s top edge");
            assertEquals(TravelPanel.x(entry.column()) + TravelPanel.CARD_WIDTH - 1, box[2],
                    entry.destination() + "'s right edge");
            assertEquals(TravelPanel.y(entry.row()) + TravelPanel.CARD_HEIGHT - 1, box[3],
                    entry.destination() + "'s bottom edge");
        }
    }

    @Test
    @DisplayName("every overlay is declared at the ascent of its row and the advance of a card")
    void theOverlaysLandOnTheCards() {
        final Map<Integer, JsonObject> providers = providers();
        final Map<String, Integer> rows = Map.of(
                Glyphs.GUI_TRAVEL_LOCKED_TOP, 0, Glyphs.GUI_TRAVEL_HERE_TOP, 0,
                Glyphs.GUI_TRAVEL_LOCKED_BOTTOM, 1, Glyphs.GUI_TRAVEL_HERE_BOTTOM, 1);
        rows.forEach((glyph, row) -> {
            final JsonObject provider = providers.get(glyph.codePointAt(0));
            assertTrue(provider != null, "U+%X is not in gui.json".formatted(glyph.codePointAt(0)));
            // A glyph's top sits at the title baseline (13) minus its ascent.
            assertEquals(TravelPanel.y(row), 13 - provider.get("ascent").getAsInt(),
                    "U+%X does not land on card row %d".formatted(glyph.codePointAt(0), row));
            final BufferedImage image = read(texture(provider.get("file").getAsString()));
            assertEquals(TravelPanel.CARD_WIDTH, image.getWidth());
            assertEquals(TravelPanel.CARD_HEIGHT, image.getHeight());
            assertEquals(image.getHeight(), provider.get("height").getAsInt(), "drawn 1:1");
        });
    }

    @Test
    @DisplayName("the surface draws one overlay per card that needs one, and none for an open card")
    void theSurfaceCarriesTheStates() {
        final List<BalloonMenu.Entry> entries = BalloonMenu.of(WorldRole.NETHER, EnumSet.of(Unlock.NETHER));
        final String surface = plain(TravelPanel.title(entries));

        assertEquals(1, count(surface, Glyphs.GUI_TRAVEL_PANEL));
        assertEquals(1, count(surface, Glyphs.GUI_TRAVEL_HERE_BOTTOM), "the Nether is HERE");
        assertEquals(1, count(surface, Glyphs.GUI_TRAVEL_LOCKED_BOTTOM), "the End is locked");
        assertEquals(0, count(surface, Glyphs.GUI_TRAVEL_LOCKED_TOP), "the overworlds are never locked");
        assertEquals(0, count(surface, Glyphs.GUI_TRAVEL_HERE_TOP));
    }

    @Test
    @DisplayName("the surface has no readable title: every child is art in nordtal:gui")
    void thereIsNoTitleText() {
        final Component title = TravelPanel.title(BalloonMenu.of(WorldRole.NORDTAL, EnumSet.noneOf(Unlock.class)));
        assertTrue(!title.children().isEmpty());
        for (final Component child : title.children()) {
            assertEquals(Glyphs.FONT_GUI, child.style().font() == null ? null : child.style().font().asString(),
                    "the balloon has no title strip and no readable title (owner's call, 2026-09-05);"
                            + " anything outside nordtal:gui would be drawn over the cards");
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private static int[] boundingBox(final BufferedImage image, final List<Integer> colours) {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                final int argb = image.getRGB(x, y);
                if ((argb >>> 24) == 0xFF && colours.contains(argb & 0xFFFFFF)) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        assertTrue(right >= 0, "no pixel of colours " + colours + " in the panel");
        return new int[] {left, top, right, bottom};
    }

    private static Map<Integer, JsonObject> providers() {
        final Map<Integer, JsonObject> out = new HashMap<>();
        final JsonObject root = JsonParser.parseString(readText(ASSETS.resolve("font/gui.json"))).getAsJsonObject();
        for (final JsonElement element : root.getAsJsonArray("providers")) {
            final JsonObject provider = element.getAsJsonObject();
            if ("bitmap".equals(provider.get("type").getAsString())) {
                out.put(provider.getAsJsonArray("chars").get(0).getAsString().codePointAt(0), provider);
            }
        }
        return out;
    }

    private static Path texture(final String id) {
        return ASSETS.resolve("textures").resolve(id.substring(id.indexOf(':') + 1));
    }

    private static int count(final String text, final String glyph) {
        return (int) text.codePoints().filter(codePoint -> codePoint == glyph.codePointAt(0)).count();
    }

    private static String plain(final Component component) {
        return ((TextComponent) component.children().get(0)).content();
    }

    private static int rgb(final int r, final int g, final int b) {
        return (r << 16) | (g << 8) | b;
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

    private static String readText(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
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
}
