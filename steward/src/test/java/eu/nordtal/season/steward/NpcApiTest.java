package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.Notifications;
import eu.nordtal.season.database.notify.PostgresNotifications;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Tests every action of Steward's NPC editor against its routes and the real database. */
class NpcApiTest extends WebTestSupport {

    private static final String ADMIN_PLAYER = "00000000-0000-0000-0000-0000000000c1";

    @Test
    void anNpcIsPublishedAsItsNextVersionAndTheGameIsTold() throws Exception {
        final UUID id = UUID.randomUUID();
        final long journaled = count("SELECT count(*) FROM journal_entry WHERE action = 'SAVE_NPC'");
        try (Notifications listening = PostgresNotifications.connector(
                        postgres.jdbcUrl(), postgres.username(), postgres.password(), 5, "npc-api-test")
                .listen(Set.of(Channel.NPC))) {
            final HttpResponse<String> first = put(npcs(id), publication(id, "Hello there.", 0));
            assertEquals(200, first.statusCode(), first.body());
            assertEquals(
                    1,
                    GSON.fromJson(first.body(), JsonObject.class).get("version").getAsInt());
            assertTrue(listening.awaitNotification(Duration.ofSeconds(10)), "the game heard of no change");
        }
        final HttpResponse<String> second = put(npcs(id), publication(id, "Welcome back.", 1));
        assertEquals(200, second.statusCode(), second.body());

        final JsonObject read = GSON.fromJson(get(npcs(id)).body(), JsonObject.class);
        assertEquals(2, read.get("version").getAsInt());
        assertEquals("Welcome back.", greeting(read.getAsJsonObject("npc")));
        assertTrue(
                GSON.fromJson(get("/api/npcs").body(), JsonArray.class).asList().stream()
                        .anyMatch(view -> view.getAsJsonObject()
                                .getAsJsonObject("npc")
                                .get("id")
                                .getAsString()
                                .equals(id.toString())),
                "the list holds the new NPC");
        assertEquals(journaled + 2, count("SELECT count(*) FROM journal_entry WHERE action = 'SAVE_NPC'"));

        final HttpResponse<String> stale = put(npcs(id), publication(id, "Good day.", 1));
        assertEquals(409, stale.statusCode(), stale.body());
        assertTrue(stale.body().contains("Ida"), stale.body());
        assertTrue(stale.body().contains("\"code\":\"NPC_CHANGED\""), stale.body());

        removedKeepingItsVersions(id);
    }

    private static void removedKeepingItsVersions(final UUID id) throws Exception {
        final JsonArray versions = GSON.fromJson(get(npcs(id) + "/versions").body(), JsonArray.class);
        assertEquals(2, versions.size());
        assertEquals(2, versions.get(0).getAsJsonObject().get("number").getAsInt());
        assertFalse(versions.get(0).getAsJsonObject().get("removal").getAsBoolean());
        assertEquals("Hello there.", greeting(versions.get(1).getAsJsonObject().getAsJsonObject("npc")));

        assertEquals(204, delete(http, npcs(id)).statusCode());
        assertEquals(404, get(npcs(id)).statusCode());
        assertEquals(404, delete(http, npcs(id)).statusCode());
        final JsonArray kept = GSON.fromJson(get(npcs(id) + "/versions").body(), JsonArray.class);
        assertEquals(3, kept.size());
        assertTrue(kept.get(0).getAsJsonObject().get("removal").getAsBoolean());
    }

    @Test
    void aDraftThatTheGameCannotPlayIsRefusedWithItsProblem() throws Exception {
        final UUID id = UUID.randomUUID();
        final HttpResponse<String> menu =
                put(npcs(id), publication(id, "Hi.", 0).replace("\"menu\":\"objectives\"", "\"menu\":\"bank\""));
        assertEquals(400, menu.statusCode(), menu.body());
        assertTrue(menu.body().contains("bank"), menu.body());

        final HttpResponse<String> pitch =
                put(npcs(id), publication(id, "Hi.", 0).replace("1.2", "7"));
        assertEquals(400, pitch.statusCode(), pitch.body());
        assertTrue(pitch.body().contains("pitch"), pitch.body());
        assertTrue(pitch.body().contains("\"code\":\"NPC_REFUSED\""), pitch.body());

        final HttpResponse<String> skin = put(
                npcs(id),
                publication(id, "Hi.", 0).replace("\"pose\"", "\"skin\":\"" + UUID.randomUUID() + "\",\"pose\""));
        assertEquals(400, skin.statusCode(), skin.body());

        final HttpResponse<String> elsewhere = put(npcs(UUID.randomUUID()), publication(id, "Hi.", 0));
        assertEquals(400, elsewhere.statusCode(), elsewhere.body());
        assertEquals(400, put(npcs(id), "{\"npc\":").statusCode());
        assertEquals(404, get(npcs(id)).statusCode(), "a refused NPC is not written");
    }

    @Test
    void theMenusAreThoseTheGameRegistered() throws Exception {
        final JsonArray menus = GSON.fromJson(get("/api/npc-menus").body(), JsonArray.class);
        final JsonObject objectives = menus.asList().stream()
                .map(menu -> menu.getAsJsonObject())
                .filter(menu -> menu.get("key").getAsString().equals("objectives"))
                .findFirst()
                .orElseThrow();
        assertEquals("Ziele", objectives.getAsJsonObject("hint").get("de").getAsString());
    }

    @Test
    void aTextIsMeasuredAgainstThePanel() throws Exception {
        final HttpResponse<String> measured = post(
                "/api/npc-text/measure",
                "[{\"text\":\"Ida\",\"kind\":\"NAME\",\"answers\":0}," + "{\"text\":\"" + "word ".repeat(80)
                        + "\",\"kind\":\"BODY\",\"answers\":2}]");
        assertEquals(200, measured.statusCode(), measured.body());
        final JsonArray answers = GSON.fromJson(measured.body(), JsonArray.class);
        assertTrue(answers.get(0).getAsJsonObject().get("fits").getAsBoolean());
        assertTrue(answers.get(1).getAsJsonObject().get("fits").getAsBoolean(), "a long text turns its pages");
        assertEquals(3, answers.get(1).getAsJsonObject().get("lineLimit").getAsInt());
        assertEquals(
                400,
                post("/api/npc-text/measure", "[{\"text\":\"Hi\",\"kind\":\"BODY\",\"answers\":5}]")
                        .statusCode());
    }

    @Test
    void anUploadedPortraitIsStoredServedAndWornAndAnythingElseRefused() throws Exception {
        final byte[] png = png(64, 64);
        final HttpResponse<String> uploaded = uploadTo("/api/npc-artworks", png);
        assertEquals(201, uploaded.statusCode(), uploaded.body());
        final JsonObject artwork = GSON.fromJson(uploaded.body(), JsonObject.class);
        final HttpResponse<byte[]> served = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webPort
                                + artwork.get("address").getAsString()))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, served.statusCode());
        assertTrue(java.util.Arrays.equals(png, served.body()));

        assertEquals(400, uploadTo("/api/npc-artworks", png(64, 32)).statusCode(), "not square");
        assertEquals(400, uploadTo("/api/npc-artworks", png(32, 32)).statusCode(), "too small to draw");
        assertEquals(400, uploadTo("/api/npc-artworks", png(1025, 1025)).statusCode(), "too large");
        assertEquals(400, uploadTo("/api/npc-artworks", oversized()).statusCode(), "over 1 MB");
        assertEquals(400, uploadTo("/api/npc-artworks", new byte[] {1, 2, 3}).statusCode(), "no PNG");
        assertEquals(404, get("/api/npc-artworks/" + UUID.randomUUID()).statusCode());

        final UUID id = UUID.randomUUID();
        final String plated = "\"plate\":{\"colour\":\"ROSE\",\"icon\":\"HEART\"},\"pose\"";
        final HttpResponse<String> worn = put(
                npcs(id),
                publication(id, "Hi.", 0)
                        .replace(
                                "\"pose\"", "\"artwork\":\"" + artwork.get("id").getAsString() + "\"," + plated));
        assertEquals(200, worn.statusCode(), worn.body());
        final JsonObject npc = GSON.fromJson(worn.body(), JsonObject.class).getAsJsonObject("npc");
        assertEquals(artwork.get("id").getAsString(), npc.get("artwork").getAsString());
        assertEquals("HEART", npc.getAsJsonObject("plate").get("icon").getAsString());

        final UUID other = UUID.randomUUID();
        final HttpResponse<String> lost = put(
                npcs(other),
                publication(other, "Hi.", 0).replace("\"pose\"", "\"artwork\":\"" + UUID.randomUUID() + "\",\"pose\""));
        assertEquals(400, lost.statusCode(), lost.body());
    }

    @Test
    void anUploadedSkinIsStoredAndServedAndAnythingElseRefused() throws Exception {
        final byte[] png = png(64, 64);
        final HttpResponse<String> uploaded = upload(png, "SLIM");
        assertEquals(201, uploaded.statusCode(), uploaded.body());
        final JsonObject skin = GSON.fromJson(uploaded.body(), JsonObject.class);
        assertEquals("SLIM", skin.get("model").getAsString());
        final HttpResponse<byte[]> served = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webPort
                                + skin.get("address").getAsString()))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, served.statusCode());
        assertTrue(java.util.Arrays.equals(png, served.body()));

        assertEquals(400, upload(png(64, 32), "CLASSIC").statusCode());
        assertEquals(400, upload(png, "WIDE").statusCode());
        assertEquals(404, get("/api/npc-skins/" + UUID.randomUUID()).statusCode());
        final HttpResponse<String> nobody = post("/api/npc-skins/copy", "{\"player\":\"not a name!\"}");
        assertEquals(404, nobody.statusCode(), nobody.body());

        final UUID id = UUID.randomUUID();
        final HttpResponse<String> dressed = put(
                npcs(id),
                publication(id, "Hi.", 0)
                        .replace("\"pose\"", "\"skin\":\"" + skin.get("id").getAsString() + "\",\"pose\""));
        assertEquals(200, dressed.statusCode(), dressed.body());
        assertEquals(
                "SLIM",
                GSON.fromJson(dressed.body(), JsonObject.class)
                        .getAsJsonObject("npc")
                        .get("skinModel")
                        .getAsString(),
                "the model is the skin's, not the draft's");
    }

    @Test
    void aPreviewIsAskedOfTheSmpForTheAdminsOwnPlayerOnly() throws Exception {
        final UUID id = UUID.randomUUID();
        final String preview = "{\"npc\":" + npc(id, "Hi.") + ",\"language\":\"de\"}";
        sql("INSERT INTO discord_user (discord_id) VALUES ('1') ON CONFLICT DO NOTHING");
        try {
            assertEquals(409, post("/api/npc-preview", preview).statusCode(), "the admin is not linked");
            sql("INSERT INTO account_link (discord_id, mc_uuid, mc_name) VALUES ('1', '" + ADMIN_PLAYER + "', 'Ada')");
            assertEquals(409, post("/api/npc-preview", preview).statusCode(), "the admin is not on the SMP");
            sql("INSERT INTO online_player (mc_uuid, mc_name, subject, updated) VALUES ('" + ADMIN_PLAYER
                    + "', 'Ada', 'smp', now())");
            assertEquals(
                    400,
                    post("/api/npc-preview", preview.replace("\"de\"", "\"fr\""))
                            .statusCode());
            assertEquals(
                    400, post("/api/npc-preview", preview.replace("1.2", "9")).statusCode());

            final Inbox<SmpRequest> smp = Inbox.over(WebFixture.postgres.dataSource(), SmpRequest.TABLE);
            final CompletableFuture<HttpResponse<String>> asked = CompletableFuture.supplyAsync(() -> {
                try {
                    return post("/api/npc-preview", preview);
                } catch (final Exception failed) {
                    throw new IllegalStateException(failed);
                }
            });
            final SmpRequest.PreviewNpc shown = claim(smp);
            assertEquals(202, asked.get().statusCode(), asked.get().body());
            assertEquals(ADMIN_PLAYER, shown.player().value().toString());
            assertEquals("de", shown.language());
            assertEquals(id, shown.npc().id());
            assertEquals(404, get(npcs(id)).statusCode(), "a preview publishes nothing");
        } finally {
            sql("DELETE FROM online_player WHERE mc_uuid = '" + ADMIN_PLAYER + "'");
            sql("DELETE FROM account_link WHERE discord_id = '1'");
        }
    }

    private static String greeting(final JsonObject npc) {
        return npc.getAsJsonObject("dialogue")
                .getAsJsonArray("steps")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("text")
                .get("en")
                .getAsString();
    }

    /** Claims requests the way the SMP does and settles the first preview. */
    private static SmpRequest.PreviewNpc claim(final Inbox<SmpRequest> smp) throws Exception {
        for (int tries = 0; tries < 100; tries++) {
            final Optional<Request<SmpRequest>> claimed = smp.claim();
            if (claimed.isEmpty()) {
                Thread.sleep(20);
            } else if (claimed.get().payload() instanceof final SmpRequest.PreviewNpc preview) {
                smp.settle(claimed.get().id(), Outcome.done(null));
                return preview;
            } else {
                smp.settle(claimed.get().id(), Outcome.failed(null));
            }
        }
        throw new AssertionError("Steward asked the SMP for no preview");
    }

    private static HttpResponse<String> upload(final byte[] png, final String model) throws Exception {
        return uploadTo("/api/npc-skins?model=" + model, png);
    }

    private static HttpResponse<String> uploadTo(final String path, final byte[] png) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webPort + path))
                        .header("Content-Type", "image/png")
                        .header(
                                "X-Steward-CSRF",
                                GSON.fromJson(get("/api/me").body(), JsonObject.class)
                                        .get("csrf")
                                        .getAsString())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(png))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String npcs(final UUID id) {
        return "/api/npcs/" + id;
    }

    private static String publication(final UUID id, final String greeting, final int version) {
        return "{\"npc\":" + npc(id, greeting) + ",\"version\":" + version + "}";
    }

    private static String npc(final UUID id, final String greeting) {
        return "{\"id\":\"" + id + "\",\"name\":{\"en\":\"Ida\",\"de\":\"Ida\"},"
                + "\"place\":{\"world\":\"world\",\"x\":1.5,\"y\":64,\"z\":-3.5,\"yaw\":90},"
                + "\"pose\":\"STAND\",\"pitch\":1.2,"
                + "\"equipment\":{\"HAND\":{\"item\":\"minecraft:iron_sword\",\"data\":\"AAAA\"}},"
                + "\"dialogue\":{\"start\":\"hello\",\"steps\":["
                + "{\"id\":\"hello\",\"x\":0,\"y\":0,\"text\":{\"en\":\"" + greeting + "\",\"de\":\"[de] Hello.\"},"
                + "\"answers\":[{\"text\":{\"en\":\"Show me\",\"de\":\"[de] Show me\"},\"next\":\"menu\"},"
                + "{\"text\":{\"en\":\"Bye\",\"de\":\"[de] Bye\"}}]},"
                + "{\"id\":\"menu\",\"x\":200,\"y\":0,\"text\":{\"en\":\"Here.\",\"de\":\"[de] Here.\"},"
                + "\"answers\":[],\"menu\":\"objectives\"}]}}";
    }

    /** A drawable 64 pixel square padded past the 1 MiB limit, so only its size refuses it. */
    private static byte[] oversized() throws Exception {
        return java.util.Arrays.copyOf(png(64, 64), 1024 * 1024 + 1);
    }

    private static byte[] png(final int width, final int height) throws Exception {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
}
