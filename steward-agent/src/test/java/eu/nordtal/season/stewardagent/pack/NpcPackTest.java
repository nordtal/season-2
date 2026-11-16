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
        for (final NpcPack.Portrait portrait : NpcPack.PORTRAITS) {
            final BufferedImage drawn =
                    ImageIO.read(textures.resolve(portrait.file() + ".png").toFile());
            assertEquals(24, drawn.getWidth());
            assertEquals(portrait.height(), drawn.getHeight());
            assertEquals(Color.RED.getRGB(), drawn.getRGB(23, 23));
        }
        final JsonObject font = JsonParser.parseString(
                        Files.readString(root.resolve("assets/nordtal/font/npc/" + NPC + ".json")))
                .getAsJsonObject();
        assertEquals(3, font.getAsJsonArray("providers").size());
        assertEquals(
                "nordtal:npc/" + NPC + "/portrait_3.png",
                font.getAsJsonArray("providers")
                        .get(2)
                        .getAsJsonObject()
                        .get("file")
                        .getAsString());
    }

    @Test
    void theHatLayerCoversTheFace() throws IOException {
        final BufferedImage face = NpcPack.face(skin(Color.RED, Color.BLUE));

        assertEquals(Color.BLUE.getRGB(), face.getRGB(0, 0));
        assertEquals(Color.BLUE.getRGB(), face.getRGB(23, 23));
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
                    final String texture = glyph.get("texture").getAsString();
                    if (texture.startsWith("nordtal:npc/portrait")) {
                        declared.add(new NpcPack.Portrait(
                                texture.substring("nordtal:npc/".length(), texture.length() - ".png".length()),
                                Integer.parseInt(glyph.get("code").getAsString(), 16),
                                glyph.get("height").getAsInt(),
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
