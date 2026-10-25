package eu.nordtal.season.packrendering.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.board.BoardFrame;
import eu.nordtal.season.packrendering.hud.BossBarLine;
import eu.nordtal.season.packrendering.hud.BossBarLine.Pill;
import eu.nordtal.season.packrendering.menu.MenuTitle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Holds each preview equal to what the plugins' own render path makes of the same glyph. */
class GlyphPreviewsTest {

    private static final GlyphPreviews.Layout LAYOUT = new GlyphPreviews.Layout(3, 1, 8, "Nordtal", 180);

    private static final Component TITLE = Component.text("Nordtal");

    @TempDir
    Path directory;

    @Test
    void aRowGlyphIsDrawnOnItsRowOfTheChestPanel() {
        final JsonObject previews = GlyphPreviews.render("nordtal:gui_r{row}", Glyphs.GUI_ROW_PILL, 0, LAYOUT);

        assertEquals(
                json(MenuTitle.on(Glyphs.GUI_PANELS.get(2))
                        .rowArt(Glyphs.GUI_ROW_PILL, 1, 8, null)
                        .build(TITLE)),
                previews.get("menu"));
        assertEquals(JsonNull.INSTANCE, previews.get("hud"));
        assertEquals(JsonNull.INSTANCE, previews.get("board"));
    }

    @Test
    void aFullWindowGlyphIsThePanelAndASmallerOneIsDrawnOnTheChestPanel() {
        assertEquals(
                json(MenuTitle.on(Glyphs.GUI_PANEL_PLAIN_4).build(TITLE)),
                GlyphPreviews.render("nordtal:gui", Glyphs.GUI_PANEL_PLAIN_4, 176, LAYOUT)
                        .get("menu"));
        assertEquals(
                json(MenuTitle.on(Glyphs.GUI_PANELS.get(2))
                        .overlay(Glyphs.GUI_CARD_TOP, 8, 68)
                        .build(TITLE)),
                GlyphPreviews.render("nordtal:gui", Glyphs.GUI_CARD_TOP, 68, LAYOUT)
                        .get("menu"));
    }

    @Test
    void aBossBarGlyphIsTheIconOfAHudPill() {
        final JsonObject previews = GlyphPreviews.render("nordtal:bossbar", Glyphs.BOSSBAR_ICON_COMPASS, 9, LAYOUT);

        assertEquals(
                json(BossBarLine.render(List.of(Pill.of(Glyphs.BOSSBAR_ICON_COMPASS, "Nordtal")))),
                previews.get("hud"));
        assertEquals(JsonNull.INSTANCE, previews.get("menu"));
        assertEquals(JsonNull.INSTANCE, previews.get("board"));
    }

    @Test
    void aBoardGlyphIsPartOfTheFrame() {
        assertEquals(
                json(BoardFrame.render(180, TITLE, List.of())),
                GlyphPreviews.render("nordtal:board", Glyphs.BOARD_CORNER_TOP_LEFT, 9, LAYOUT)
                        .get("board"));
    }

    @Test
    void aGlyphOfTheDefaultFontStandsInTheMenuTitleAndInABoardLine() {
        final JsonObject previews = GlyphPreviews.render("minecraft:default", Glyphs.BADGE_DONOR_STAR, 7, LAYOUT);
        final Component line = Component.text(Glyphs.BADGE_DONOR_STAR + " Nordtal");

        assertEquals(json(MenuTitle.of(3, line)), previews.get("menu"));
        assertEquals(json(BoardFrame.render(180, TITLE, List.of(line))), previews.get("board"));
        assertEquals(JsonNull.INSTANCE, previews.get("hud"));
    }

    @Test
    void theTaskWritesTheGlyphAndItsPreviewsToTheFileItIsGiven() throws IOException {
        final Path out = directory.resolve("previews.json");

        GlyphPreviews.main(new String[] {
            "--font=nordtal:bossbar",
            "--code=FEF00",
            "--width=9",
            "--rows=3",
            "--row=1",
            "--x=8",
            "--text=Nordtal",
            "--board-width=180",
            "--out=" + out
        });

        final JsonObject written = JsonParser.parseString(Files.readString(out, StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("nordtal:bossbar", written.get("font").getAsString());
        assertEquals("FEF00", written.get("code").getAsString());
        assertEquals(
                json(BossBarLine.render(List.of(Pill.of(Glyphs.BOSSBAR_ICON_COMPASS, "Nordtal")))), written.get("hud"));
    }

    @Test
    void aFontNoPlaceDrawsIsRefused() {
        final IllegalArgumentException refusal = assertThrows(
                IllegalArgumentException.class, () -> GlyphPreviews.render("nordtal:nothing", "x", 1, LAYOUT));
        assertTrue(refusal.getMessage().contains("nordtal:nothing"), refusal.getMessage());
    }

    private static JsonElement json(final Component component) {
        return GsonComponentSerializer.gson().serializeToTree(component);
    }
}
