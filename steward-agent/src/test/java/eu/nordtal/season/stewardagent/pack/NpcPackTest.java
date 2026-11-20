package eu.nordtal.season.stewardagent.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.common.RepositoryRoot;
import eu.nordtal.season.database.npc.NpcSkin;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NpcPackTest {

    private static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @TempDir
    Path root;

    @Test
    void aSkinBecomesTheNpcsBodyTextureAndPortraitFont() throws IOException {
        final byte[] png = skin(Color.RED, null);

        NpcPack.write(root, Map.of(NPC, new NpcSkin(UUID.randomUUID(), NpcSkin.SkinModel.SLIM, encode(png))));

        final Path textures = root.resolve("assets/nordtal/textures/npc/" + NPC);
        assertTrue(java.util.Arrays.equals(png, Files.readAllBytes(textures.resolve("skin.png"))));
        final BufferedImage drawn =
                ImageIO.read(textures.resolve("portrait.png").toFile());
        assertEquals(46, drawn.getWidth());
        assertEquals(64, drawn.getHeight());
        assertEquals(Color.RED.getRGB(), drawn.getRGB(23, 18), "the face in the middle of the well");
        assertEquals(0, drawn.getRGB(23, 50), "the padding below the frame");
        final JsonObject font = JsonParser.parseString(
                        Files.readString(root.resolve("assets/nordtal/font/npc/" + NPC + ".json")))
                .getAsJsonObject();
        assertEquals(NpcPack.PORTRAITS.size(), font.getAsJsonArray("providers").size());
        for (final JsonElement provider : font.getAsJsonArray("providers")) {
            assertEquals(
                    "nordtal:npc/" + NPC + "/portrait.png",
                    provider.getAsJsonObject().get("file").getAsString());
            assertEquals(64, provider.getAsJsonObject().get("height").getAsInt());
        }
    }

    @Test
    void theHatLayerCoversTheFace() throws IOException {
        final BufferedImage bust = NpcPack.bust(skin(Color.RED, Color.BLUE), false);

        assertEquals(Color.BLUE.getRGB(), bust.getRGB(20, 15));
        assertEquals(Color.BLUE.getRGB(), bust.getRGB(9, 28));
    }

    @Test
    void anOwnPortraitHasTheFrameOfTheDefaultPortrait() throws IOException {
        final BufferedImage own = NpcPack.portrait(NpcPack.bust(skin(Color.RED, null), true));
        final BufferedImage standard =
                ImageIO.read(RepositoryRoot.resolve("resource-pack/src/assets/nordtal/textures/npc/portrait.png")
                        .toFile());

        assertEquals(standard.getWidth(), own.getWidth());
        assertEquals(standard.getHeight(), own.getHeight());
        for (int y = 0; y < own.getHeight(); y++) {
            for (int x = 0; x < own.getWidth(); x++) {
                final boolean well = x >= 3 && x < 43 && y >= 3 && y < 43;
                if (!well) {
                    assertEquals(standard.getRGB(x, y), own.getRGB(x, y), "at " + x + ", " + y);
                }
            }
        }
    }

    @Test
    void theOwnPortraitsStandWhereThePacksDefaultPortraitsStand() {
        final List<NpcPack.Portrait> declared = new ArrayList<>();
        for (final JsonElement font : JsonParser.parseString(RepositoryRoot.read("resource-pack/glyphs.json"))
                .getAsJsonObject()
                .getAsJsonArray("fonts")) {
            if (!"nordtal:npc".equals(font.getAsJsonObject().get("font").getAsString())) {
                continue;
            }
            for (final JsonElement block : font.getAsJsonObject().getAsJsonArray("blocks")) {
                for (final JsonElement each : block.getAsJsonObject().getAsJsonArray("glyphs")) {
                    final JsonObject glyph = each.getAsJsonObject();
                    if (("nordtal:npc/" + NpcPack.FILE + ".png")
                            .equals(glyph.get("texture").getAsString())) {
                        assertEquals(NpcPack.HEIGHT, glyph.get("height").getAsInt());
                        declared.add(new NpcPack.Portrait(
                                Integer.parseInt(glyph.get("code").getAsString(), 16),
                                glyph.get("ascent").getAsInt()));
                    }
                }
            }
        }

        assertEquals(declared, NpcPack.PORTRAITS);
    }

    @Test
    void theInputsNameOnlyTheSkinOfEachNpc() {
        final UUID skin = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

        assertEquals(
                "{\"" + NPC + "\":\"" + skin + "\"}",
                PackInputs.skinIds(Map.of(NPC, new NpcSkin(skin, NpcSkin.SkinModel.CLASSIC, "cG5n"))));
    }

    private static byte[] skin(final Color face, final Color hat) throws IOException {
        final BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                skin.setRGB(8 + x, 8 + y, face.getRGB());
                if (hat != null) {
                    skin.setRGB(40 + x, 8 + y, hat.getRGB());
                }
            }
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(skin, "png", out);
        return out.toByteArray();
    }

    private static String encode(final byte[] png) {
        return Base64.getEncoder().encodeToString(png);
    }
}
