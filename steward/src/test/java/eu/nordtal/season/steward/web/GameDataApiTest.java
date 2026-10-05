package eu.nordtal.season.steward.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import eu.nordtal.season.database.game.GameCatalogue;
import eu.nordtal.season.database.game.GameDataStore;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The servers' catalogues as one, and a version's icons, to signed-in admins only and cached for good. */
class GameDataApiTest extends WebTestSupport {

    private static final byte[] SHEET = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    @AfterEach
    void forget() throws Exception {
        try (var connection = WebFixture.postgres.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("DELETE FROM game_catalogue");
            statement.execute("DELETE FROM game_assets");
        }
    }

    private static GameCatalogue catalogue(final String... advancements) {
        return new GameCatalogue(
                "26.2",
                List.of("vanilla"),
                Map.of(
                        "advancement",
                        java.util.Arrays.stream(advancements)
                                .map(id -> GameCatalogue.Entry.named(id, null, null))
                                .toList()),
                Map.of());
    }

    @Test
    void theServersCatalogueIsOneAndItsIconsFollowOnceDrawn() throws Exception {
        final GameDataStore store = GameDataStore.using(WebFixture.postgres.dataSource());
        store.publish("limbo", catalogue("minecraft:story/root"));
        store.publish("smp", catalogue("minecraft:story/root", "nordtal:smp/first_night"));

        final JsonObject before = GSON.fromJson(get("/api/game-data").body(), JsonObject.class);
        assertEquals("26.2", before.get("version").getAsString());
        assertEquals(
                2,
                before.getAsJsonObject("registries")
                        .getAsJsonArray("advancement")
                        .size());
        assertTrue(before.get("icons") == null || before.get("icons").isJsonNull(), "no icons before they are drawn");

        store.storeIcons("26.2", new GameDataStore.Icons(SHEET, 32, Map.of("minecraft:stone", 0)));
        final JsonObject after = GSON.fromJson(get("/api/game-data").body(), JsonObject.class);
        final String url = after.getAsJsonObject("icons").get("url").getAsString();
        assertEquals("/api/game-data/26.2/icons.png", url);

        final HttpResponse<byte[]> sheet = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + WEB_PORT + url))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, sheet.statusCode());
        assertArrayEquals(SHEET, sheet.body());
        assertEquals("image/png", sheet.headers().firstValue("Content-Type").orElse(""));
        assertEquals(
                GameDataRoutes.ICONS_CACHE,
                sheet.headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    void nobodySignedOutSeesTheIcons() throws Exception {
        GameDataStore.using(WebFixture.postgres.dataSource())
                .storeIcons("26.2", new GameDataStore.Icons(SHEET, 32, Map.of()));

        final int status = get(browser(), "/api/game-data/26.2/icons.png").statusCode();

        assertTrue(status == 401 || status == 403, "answered " + status);
    }

    @Test
    void aVersionWithoutIconsIsNotFound() throws Exception {
        assertEquals(404, get("/api/game-data/26.9/icons.png").statusCode());
    }
}
