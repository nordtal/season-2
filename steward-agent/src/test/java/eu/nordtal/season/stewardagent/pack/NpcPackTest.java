package eu.nordtal.season.stewardagent.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.common.RepositoryRoot;
import eu.nordtal.season.database.npc.NpcArtwork;
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

        NpcPack.write(
                root,
                Map.of(NPC, new NpcSkin(UUID.randomUUID(), NpcSkin.SkinModel.SLIM, encode(png))),
                Map.of(),
                syntheticGeneric54());

        final Path textures = root.resolve("assets/nordtal/textures/npc/" + NPC);
        assertTrue(java.util.Arrays.equals(png, Files.readAllBytes(textures.resolve("skin.png"))));
        final BufferedImage drawn =
                ImageIO.read(textures.resolve("portrait.png").toFile());
        assertEquals(48, drawn.getWidth());
        assertEquals(64, drawn.getHeight());
        assertEquals(Color.RED.getRGB(), drawn.getRGB(24, 18), "the face in the middle of the well");
        assertEquals(0, drawn.getRGB(24, 52), "the padding below the frame");
        final JsonObject font = JsonParser.parseString(
                        Files.readString(root.resolve("assets/nordtal/font/npc/" + NPC + ".json")))
                .getAsJsonObject();
        final List<JsonElement> providers = font.getAsJsonArray("providers").asList();
        assertEquals(NpcPack.PORTRAITS.size() + 1, providers.size());
        assertEquals(
                "nordtal:ui_space",
                providers.getFirst().getAsJsonObject().get("id").getAsString(),
                "the moves after the portrait");
        for (final JsonElement provider : providers.subList(1, providers.size())) {
            assertEquals(
                    "nordtal:npc/" + NPC + "/portrait.png",
                    provider.getAsJsonObject().get("file").getAsString());
            assertEquals(64, provider.getAsJsonObject().get("height").getAsInt());
        }
    }

    @Test
    void aSeeThroughPixelLaysOverAnotherByItsShare() {
        assertEquals(0x80FF0000, NpcPack.sourceOver(0, 0x80FF0000), "over nothing it keeps its own alpha");
        assertEquals(0xFF80007F, NpcPack.sourceOver(0xFF0000FF, 0x80FF0000), "over an opaque pixel it stays opaque");
        final int mixed = NpcPack.sourceOver(0x800000FF, 0x80FF0000);
        assertEquals(0xC0, mixed >>> 24, "two halves cover three quarters");
        assertEquals(0xAA, mixed >> 16 & 0xFF, "the top's red weighs twice the bottom's blue");
        assertEquals(0x55, mixed & 0xFF);
    }

    @Test
    void theHatLayerCoversTheFace() throws IOException {
        final BufferedImage bust = NpcPack.bust(skin(Color.RED, Color.BLUE), false);

        assertEquals(Color.BLUE.getRGB(), bust.getRGB(18, 12));
        assertEquals(Color.BLUE.getRGB(), bust.getRGB(7, 24));
    }

    @Test
    void anOwnPortraitMatchesVanillaGeneric54CornersBevelAndSlotWell() throws IOException {
        final BufferedImage vanilla = syntheticGeneric54();
        final BufferedImage own = NpcPack.portrait(NpcPack.bust(skin(Color.RED, null), true), vanilla);

        assertEquals(NpcPack.FRAME, own.getWidth());
        assertEquals(NpcPack.HEIGHT, own.getHeight());
        for (int y = 0; y < NpcPack.VANILLA_EDGE; y++) {
            for (int x = 0; x < NpcPack.VANILLA_EDGE; x++) {
                assertEquals(vanilla.getRGB(x, y), own.getRGB(x, y), "TL (" + x + "," + y + ")");
                assertEquals(
                        vanilla.getRGB(NpcPack.VANILLA_WIDTH - NpcPack.VANILLA_EDGE + x, y),
                        own.getRGB(NpcPack.FRAME - NpcPack.VANILLA_EDGE + x, y),
                        "TR (" + x + "," + y + ")");
                assertEquals(
                        vanilla.getRGB(x, NpcPack.VANILLA_HEIGHT - NpcPack.VANILLA_EDGE + y),
                        own.getRGB(x, NpcPack.FRAME - NpcPack.VANILLA_EDGE + y),
                        "BL (" + x + "," + y + ")");
                assertEquals(
                        vanilla.getRGB(
                                NpcPack.VANILLA_WIDTH - NpcPack.VANILLA_EDGE + x,
                                NpcPack.VANILLA_HEIGHT - NpcPack.VANILLA_EDGE + y),
                        own.getRGB(NpcPack.FRAME - NpcPack.VANILLA_EDGE + x, NpcPack.FRAME - NpcPack.VANILLA_EDGE + y),
                        "BR (" + x + "," + y + ")");
            }
        }
        assertEquals(0, own.getRGB(24, 52), "padding below frame stays transparent");
    }

    @Test
    void anUploadedPictureIsTheWholeAvatarInsideTheUnchangedFrame() throws IOException {
        final BufferedImage upload = new BufferedImage(NpcPack.PICTURE, NpcPack.PICTURE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < NpcPack.PICTURE; y++) {
            for (int x = 0; x < NpcPack.PICTURE; x++) {
                upload.setRGB(x, y, 0xFF000000 | (x * 6) << 16 | (y * 6) << 8 | 0x40);
            }
        }
        final BufferedImage vanilla = syntheticGeneric54();
        final BufferedImage picture = NpcPack.artwork(png(upload));

        final BufferedImage own = NpcPack.portrait(picture, vanilla);
        final BufferedImage empty = NpcPack.portrait(
                new BufferedImage(NpcPack.PICTURE, NpcPack.PICTURE, BufferedImage.TYPE_INT_ARGB), vanilla);

        final int start = NpcPack.RIM + 1;
        final int end = start + NpcPack.PICTURE;
        for (int y = 0; y < own.getHeight(); y++) {
            for (int x = 0; x < own.getWidth(); x++) {
                final boolean inside = x >= start && x < end && y >= start && y < end;
                final int expected = inside ? upload.getRGB(x - start, y - start) : empty.getRGB(x, y);
                assertEquals(expected, own.getRGB(x, y), "at " + x + ", " + y);
            }
        }
    }

    @Test
    void theWellsSunkEdgeStillShowsWhereThePictureIsClear() throws IOException {
        final BufferedImage vanilla = syntheticGeneric54();
        final BufferedImage bust = NpcPack.bust(skin(Color.RED, null), false);
        assertEquals(0, bust.getRGB(0, 0) >>> 24, "the bust leaves the well's corner clear");

        final BufferedImage own = NpcPack.portrait(bust, vanilla);
        final BufferedImage blank = NpcPack.portrait(
                new BufferedImage(NpcPack.PICTURE, NpcPack.PICTURE, BufferedImage.TYPE_INT_ARGB), vanilla);

        final int rim = NpcPack.RIM;
        final int wellEnd = rim + NpcPack.WELL - 1;
        assertEquals(vanilla.getRGB(NpcPack.VANILLA_SLOT_X, NpcPack.VANILLA_SLOT_Y), own.getRGB(rim, rim));
        assertEquals(blank.getRGB(24, rim), own.getRGB(24, rim));
        assertTrue(blank.getRGB(24, rim) != blank.getRGB(24, rim + 1), "the top row is sunk");
        assertEquals(
                vanilla.getRGB(
                        NpcPack.VANILLA_SLOT_X + NpcPack.VANILLA_SLOT_SIZE - 1,
                        NpcPack.VANILLA_SLOT_Y + NpcPack.VANILLA_SLOT_SIZE - 1),
                own.getRGB(wellEnd, wellEnd),
                "the bottom-right edge uses vanilla's slot highlight and is never clipped by the bust");
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
    void theInputsNameOnlyTheSkinAndTheArtworkOfEachNpc() {
        final UUID skin = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        final UUID artwork = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

        assertEquals(
                "{\"" + NPC + "\":\"" + skin + "\"}",
                PackInputs.ids(Map.of(NPC, new NpcSkin(skin, NpcSkin.SkinModel.CLASSIC, "cG5n")), NpcSkin::id));
        assertEquals(
                "{\"" + NPC + "\":\"" + artwork + "\"}",
                PackInputs.ids(Map.of(NPC, new NpcArtwork(artwork, "cG5n")), NpcArtwork::id));
    }

    @Test
    void anArtworkTakesThePortraitsPlaceAveragedToItsSize() throws IOException {
        final BufferedImage upload = new BufferedImage(72, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 72; x++) {
                upload.setRGB(x, y, (x + y) % 2 == 0 ? 0xFFFF0000 : 0xFF0000FF);
            }
        }
        upload.setRGB(0, 0, 0);
        upload.setRGB(1, 0, 0);
        upload.setRGB(0, 1, 0);
        upload.setRGB(1, 1, 0);
        final byte[] skin = skin(Color.RED, null);

        NpcPack.write(
                root,
                Map.of(NPC, new NpcSkin(UUID.randomUUID(), NpcSkin.SkinModel.CLASSIC, encode(skin))),
                Map.of(NPC, new NpcArtwork(UUID.randomUUID(), encode(png(upload)))),
                syntheticGeneric54());

        final Path textures = root.resolve("assets/nordtal/textures/npc/" + NPC);
        assertTrue(Files.exists(textures.resolve("skin.png")), "the skin still dresses the figure");
        final BufferedImage drawn =
                ImageIO.read(textures.resolve("portrait.png").toFile());
        final int offset = NpcPack.RIM + 1;
        assertEquals(
                0xFF800080,
                drawn.getRGB(offset + 18, offset + 18),
                "each pixel the average of the two by two below it");
        assertEquals(0xFF000000, drawn.getRGB(offset, offset) & 0xFF000000, "a clear upload pixel shows the well");
    }

    @Test
    void anArtworkWithoutSkinGetsAPortraitButNoBody() throws IOException {
        final BufferedImage upload = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 12; x++) {
                upload.setRGB(x, y, x < 6 ? 0xFF00FF00 : 0xFFFFFFFF);
            }
        }

        NpcPack.write(
                root,
                Map.of(),
                Map.of(NPC, new NpcArtwork(UUID.randomUUID(), encode(png(upload)))),
                syntheticGeneric54());

        final Path textures = root.resolve("assets/nordtal/textures/npc/" + NPC);
        assertTrue(Files.notExists(textures.resolve("skin.png")));
        final BufferedImage drawn =
                ImageIO.read(textures.resolve("portrait.png").toFile());
        final int offset = NpcPack.RIM + 1;
        assertEquals(0xFF00FF00, drawn.getRGB(offset + 17, offset + 24), "a small upload grows in whole blocks");
        assertEquals(0xFFFFFFFF, drawn.getRGB(offset + 18, offset + 24));
        assertTrue(Files.exists(root.resolve("assets/nordtal/font/npc/" + NPC + ".json")));
    }

    @Test
    void aClearPixelOfAnArtworkDoesNotDarkenItsEdge() throws IOException {
        final BufferedImage upload = new BufferedImage(72, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 36; x += 2) {
                upload.setRGB(x, y, 0xFFFF0000);
            }
        }

        final BufferedImage picture = NpcPack.artwork(png(upload));

        assertEquals(0x80FF0000, picture.getRGB(0, 0), "half red, half clear black stays red at half alpha");
        assertEquals(0, picture.getRGB(35, 0), "a clear block stays clear");
    }

    @Test
    void anArtworkThatIsNotSquareFailsTheBuild() throws IOException {
        final byte[] wide = png(new BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB));

        assertThrows(
                IOException.class,
                () -> NpcPack.write(
                        root,
                        Map.of(),
                        Map.of(NPC, new NpcArtwork(UUID.randomUUID(), encode(wide))),
                        syntheticGeneric54()));
    }

    private static BufferedImage syntheticGeneric54() {
        final BufferedImage image =
                new BufferedImage(NpcPack.VANILLA_WIDTH, NpcPack.VANILLA_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < NpcPack.VANILLA_HEIGHT; y++) {
            for (int x = 0; x < NpcPack.VANILLA_WIDTH; x++) {
                final int color;
                if (x == 0 || y == 0 || x == NpcPack.VANILLA_WIDTH - 1 || y == NpcPack.VANILLA_HEIGHT - 1) {
                    color = 0xFF000000;
                } else if (x <= 2 || y <= 2) {
                    color = 0xFFFFFFFF;
                } else if (x >= NpcPack.VANILLA_WIDTH - 3 || y >= NpcPack.VANILLA_HEIGHT - 3) {
                    color = 0xFF555555;
                } else {
                    color = 0xFFC6C6C6;
                }
                image.setRGB(x, y, color);
            }
        }
        for (int dy = 0; dy < NpcPack.VANILLA_SLOT_SIZE; dy++) {
            for (int dx = 0; dx < NpcPack.VANILLA_SLOT_SIZE; dx++) {
                final int color;
                if ((dx == NpcPack.VANILLA_SLOT_SIZE - 1 && dy == 0)
                        || (dx == 0 && dy == NpcPack.VANILLA_SLOT_SIZE - 1)) {
                    color = 0xFF8B8B8B;
                } else if (dx == 0 || dy == 0) {
                    color = 0xFF373737;
                } else if (dx == NpcPack.VANILLA_SLOT_SIZE - 1 || dy == NpcPack.VANILLA_SLOT_SIZE - 1) {
                    color = 0xFFFFFFFF;
                } else {
                    color = 0xFF8B8B8B;
                }
                image.setRGB(NpcPack.VANILLA_SLOT_X + dx, NpcPack.VANILLA_SLOT_Y + dy, color);
            }
        }
        return image;
    }

    private static byte[] png(final BufferedImage image) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
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
