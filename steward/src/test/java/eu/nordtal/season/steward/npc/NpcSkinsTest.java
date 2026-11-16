package eu.nordtal.season.steward.npc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.nordtal.season.common.http.WebClient;
import eu.nordtal.season.database.npc.NpcSkin;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests the checks of an uploaded skin, and the copy of an account's skin over a stand-in for Mojang. */
class NpcSkinsTest {

    private static final String NOTCH = "069a79f444e94726a5befca90e38aaf5";

    private HttpServer mojang;
    private NpcSkins skins;
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private byte[] skin;
    private String model = "slim";
    private volatile boolean down;

    @BeforeEach
    void start() throws IOException {
        skin = png(64, 64);
        mojang = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        final String base = "http://127.0.0.1:" + mojang.getAddress().getPort();
        mojang.createContext("/names/", exchange -> {
            asked.add(exchange.getRequestURI().getPath());
            if (exchange.getRequestURI().getPath().endsWith("/Notch")) {
                answer(
                        exchange,
                        200,
                        ("{\"id\":\"" + NOTCH + "\",\"name\":\"Notch\"}").getBytes(StandardCharsets.UTF_8));
            } else {
                answer(exchange, 404, new byte[0]);
            }
        });
        mojang.createContext("/profiles/", exchange -> {
            asked.add(exchange.getRequestURI().getPath());
            if (down) {
                answer(exchange, 503, new byte[0]);
                return;
            }
            if (!exchange.getRequestURI().getPath().endsWith(NOTCH)) {
                answer(exchange, 204, new byte[0]);
                return;
            }
            final String metadata = model == null ? "" : ",\"metadata\":{\"model\":\"" + model + "\"}";
            final String textures = "{\"textures\":{\"SKIN\":{\"url\":\"" + base + "/texture/abc\"" + metadata + "}}}";
            final String value = Base64.getEncoder().encodeToString(textures.getBytes(StandardCharsets.UTF_8));
            answer(
                    exchange,
                    200,
                    ("{\"id\":\"" + NOTCH + "\",\"properties\":[{\"name\":\"textures\",\"value\":\"" + value + "\"}]}")
                            .getBytes(StandardCharsets.UTF_8));
        });
        mojang.createContext("/texture/", exchange -> answer(exchange, 200, skin));
        mojang.start();
        skins = new NpcSkins(
                base + "/profiles/", base + "/names/", "127.0.0.1", WebClient.create(Duration.ofSeconds(5)));
    }

    @AfterEach
    void stop() {
        mojang.stop(0);
    }

    @Test
    void anAccountsSkinIsCopiedByNameOrUuidWithItsArmShape() throws IOException {
        final NpcSkin byName = skins.copy(" Notch ");
        assertEquals(NpcSkin.SkinModel.SLIM, byName.model());
        assertArrayEquals(skin, Base64.getDecoder().decode(byName.png()));
        assertEquals(List.of("/names/Notch", "/profiles/" + NOTCH), asked);

        asked.clear();
        model = null;
        final NpcSkin byUuid = skins.copy("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        assertEquals(NpcSkin.SkinModel.CLASSIC, byUuid.model());
        assertEquals(List.of("/profiles/" + NOTCH), asked, "a UUID needs no name lookup");
    }

    @Test
    void anUnknownAccountIsToldApartFromMojangFailing() throws IOException {
        assertThrows(NpcSkinSource.UnknownPlayer.class, () -> skins.copy("Nobody"));
        assertThrows(NpcSkinSource.UnknownPlayer.class, () -> skins.copy("00000000000000000000000000000001"));
        assertThrows(NpcSkinSource.UnknownPlayer.class, () -> skins.copy("not a name!"));

        skin = png(64, 32);
        assertThrows(NpcSkinSource.NoSkin.class, () -> skins.copy("Notch"), "an old 64 by 32 skin cannot be worn");

        down = true;
        final IOException failing = assertThrows(IOException.class, () -> skins.copy("Notch"));
        assertFalse(
                failing instanceof NpcSkinSource.UnknownPlayer || failing instanceof NpcSkinSource.NoSkin,
                "Mojang failing is neither an unknown name nor no skin");
    }

    @Test
    void anUploadIsTakenOnlyAsASixtyFourSquarePng() throws IOException {
        final NpcSkin taken = skins.uploaded(skin, NpcSkin.SkinModel.CLASSIC);
        assertArrayEquals(skin, Base64.getDecoder().decode(taken.png()));
        assertThrows(NpcSkinSource.NotASkin.class, () -> skins.uploaded(png(64, 32), NpcSkin.SkinModel.CLASSIC));
        assertThrows(
                NpcSkinSource.NotASkin.class,
                () -> skins.uploaded("GIF89a".getBytes(StandardCharsets.UTF_8), NpcSkin.SkinModel.CLASSIC));
        final byte[] large = new byte[NpcSkins.LIMIT + 1];
        System.arraycopy(skin, 0, large, 0, skin.length);
        assertThrows(NpcSkinSource.NotASkin.class, () -> skins.uploaded(large, NpcSkin.SkinModel.SLIM));
    }

    static byte[] png(final int width, final int height) throws IOException {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(8, 8, 0xFF336699);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private static void answer(final HttpExchange exchange, final int status, final byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, status == 204 ? -1 : body.length);
        if (status != 204) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }
}
