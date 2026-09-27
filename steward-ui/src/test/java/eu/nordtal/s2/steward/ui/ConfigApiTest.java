package eu.nordtal.s2.steward.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The configuration editor: listing, reading, and saving a file behind an optimistic revision. */
class ConfigApiTest extends StewardUiTestSupport {

    @Test
    void everyConfigInTheStackIsListed() throws Exception {
        final JsonArray files = GSON.fromJson(get("/api/config").body(), JsonArray.class);

        final List<String> paths = files.asList().stream()
                .map(file -> file.getAsJsonObject().get("path").getAsString())
                .toList();
        assertEquals(List.of("smp/nordtal-smp/config.yml", "steward-worker/steward.yml"), paths);
    }

    @Test
    void theListingCarriesReadableAndWritable() throws Exception {
        // Both, and both on every row: unreadable draws as dead, read-only draws as a form with no save.
        final JsonArray files = GSON.fromJson(get("/api/config").body(), JsonArray.class);

        assertFalse(files.isEmpty(), "nothing was listed, so nothing was asserted");
        for (final JsonElement listed : files) {
            final JsonObject file = listed.getAsJsonObject();
            final String path = file.get("path").getAsString();
            assertTrue(file.has("readable"), path + " does not say whether it can be read");
            assertTrue(file.has("writable"), path + " does not say whether it can be written");
            // These fixtures are ordinary files this process owns, so both are true here.
            assertTrue(file.get("readable").getAsBoolean(), path + " should be readable");
            assertTrue(file.get("writable").getAsBoolean(), path + " should be writable");
        }
    }

    @Test
    void aPathWithSlashesInItReachesTheFile() throws Exception {
        // Javalin's `{name}` stops at a slash and `<name>` does not; a plugin's config is two directories down.
        final HttpResponse<String> response = get("/api/config/smp/nordtal-smp/config.yml");

        assertEquals(200, response.statusCode(), response.body());
        final JsonObject document = GSON.fromJson(response.body(), JsonObject.class);
        assertEquals("smp", document.get("service").getAsString());
        assertEquals("nordtal-smp/config.yml", document.get("name").getAsString());
        final JsonObject motd = document.getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals("MOTD", motd.get("label").getAsString());
        assertEquals("Nordtal\nSeason 2", motd.get("value").getAsString());
        assertTrue(motd.get("editable").getAsBoolean(), "a block scalar is editable");
    }

    @Test
    void aSecretIsNotSentToTheBrowser() throws Exception {
        final JsonObject document =
                GSON.fromJson(get("/api/config/steward-worker/steward.yml").body(), JsonObject.class);

        final JsonObject token = entry(document, "token");
        assertTrue(token.get("secret").getAsBoolean());
        assertTrue(token.get("filled").getAsBoolean(), "the page still has to be able to say it is set");
        assertFalse(token.has("value"), "the value itself is not in the answer: " + token);
        assertFalse(document.toString().contains("hunter2"), "the secret is nowhere in the body");
    }

    @Test
    void aChangeIsWrittenThrough() throws Exception {
        final HttpResponse<String> saved = put(
                "/api/config/steward-worker/steward.yml",
                "{\"revision\": \"" + revisionOf("steward-worker/steward.yml")
                        + "\", \"changes\": {\"port\": \"9099\"}}");

        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals(
                "9099",
                entry(GSON.fromJson(saved.body(), JsonObject.class), "port")
                        .get("value")
                        .getAsString());
        assertTrue(Files.readString(configRoot.resolve("steward-worker/steward.yml"))
                .contains("port: 9099"));
        // The comment above it survives, which is why this reads the file instead of re-dumping it.
        assertTrue(Files.readString(configRoot.resolve("steward-worker/steward.yml"))
                .contains("# The worker."));
    }

    @Test
    void aListIsSavedAsAList() throws Exception {
        final HttpResponse<String> saved = put(
                "/api/config/steward-worker/steward.yml",
                "{\"revision\": \"" + revisionOf("steward-worker/steward.yml")
                        + "\", \"changes\": {\"stop-services\": [\"smp\", \"limbo\","
                        + " \"hunger-games\"]}}");

        assertEquals(200, saved.statusCode(), saved.body());
        assertTrue(Files.readString(configRoot.resolve("steward-worker/steward.yml"))
                .contains("- hunger-games"));
    }

    @Test
    void theWrongShapeIsRefused() throws Exception {
        final HttpResponse<String> refused = put(
                "/api/config/steward-worker/steward.yml",
                "{\"revision\": \"" + revisionOf("steward-worker/steward.yml")
                        + "\", \"changes\": {\"stop-services\": \"smp\"}}");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("stop-services"), refused.body());
    }

    /** The revision the interface handed out for a file, read through the API so a missing one fails. */
    private String revisionOf(final String file) throws Exception {
        final JsonObject document = GSON.fromJson(get("/api/config/" + file).body(), JsonObject.class);
        return document.get("revision").getAsString();
    }

    @Test
    void aSaveHasToSayWhatItWasLastShown() throws Exception {
        // Not optional with a default: an omittable revision is one a client can forget.
        final byte[] before = Files.readAllBytes(configRoot.resolve("steward-worker/steward.yml"));

        final HttpResponse<String> refused =
                put("/api/config/steward-worker/steward.yml", "{\"changes\": {\"port\": \"9098\"}}");

        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("revision"), refused.body());
        assertArrayEquals(
                before,
                Files.readAllBytes(configRoot.resolve("steward-worker/steward.yml")),
                "a 400 still wrote the file");
    }

    @Test
    void twoAdminsOnOneFile() throws Exception {
        // Two forms open on one file: the second save simply overwrites the first with nothing saying so.
        final String whatTheSecondFormShows = revisionOf("steward-worker/steward.yml");

        assertEquals(
                200,
                put(
                                "/api/config/steward-worker/steward.yml",
                                "{\"revision\": \"" + whatTheSecondFormShows + "\", \"changes\": {\"port\": \"9097\"}}")
                        .statusCode());
        final byte[] afterTheFirstSave = Files.readAllBytes(configRoot.resolve("steward-worker/steward.yml"));

        final HttpResponse<String> refused = put(
                "/api/config/steward-worker/steward.yml",
                "{\"revision\": \"" + whatTheSecondFormShows + "\", \"changes\": {\"token\": \"hunter3\"}}");

        assertEquals(409, refused.statusCode(), refused.body());
        assertArrayEquals(
                afterTheFirstSave,
                Files.readAllBytes(configRoot.resolve("steward-worker/steward.yml")),
                "the refused save wrote anyway - the operator is told nothing was saved while the"
                        + " other admin's change is being undone underneath them");
        assertTrue(refused.body().contains("changed by somebody else"), refused.body());

        // The way out: the page re-reads the file and the same save goes through against the fresh revision.
        final HttpResponse<String> retried = put(
                "/api/config/steward-worker/steward.yml",
                "{\"revision\": \"" + revisionOf("steward-worker/steward.yml")
                        + "\", \"changes\": {\"token\": \"hunter3\"}}");
        assertEquals(200, retried.statusCode(), retried.body());
        assertTrue(
                Files.readString(configRoot.resolve("steward-worker/steward.yml"))
                        .contains("port: 9097"),
                "the retry undid the other admin's change after all");
    }

    @Test
    void aSaveHandsBackWhatTheNextOneNeeds() throws Exception {
        // Otherwise every save needs a mandatory reload, and a 409 on the operator's own edit teaches nothing.
        final HttpResponse<String> first = put(
                "/api/config/steward-worker/steward.yml",
                "{\"revision\": \"" + revisionOf("steward-worker/steward.yml")
                        + "\", \"changes\": {\"port\": \"9096\"}}");
        assertEquals(200, first.statusCode(), first.body());

        final String handedBack =
                GSON.fromJson(first.body(), JsonObject.class).get("revision").getAsString();
        final HttpResponse<String> second = put(
                "/api/config/steward-worker/steward.yml",
                "{\"revision\": \"" + handedBack + "\", \"changes\": {\"port\": \"9095\"}}");

        assertEquals(200, second.statusCode(), second.body());
    }

    @Test
    void nothingOutsideTheMountCanBeReached() throws Exception {
        assertEquals(404, get("/api/config/steward-worker/nope.yml").statusCode());
        // The lookup compares against what was found, not a path resolved against the root.
        assertFalse(get("/api/config/..%2f..%2fetc%2fpasswd").statusCode() == 200);
        assertFalse(get("/api/config/steward-worker/../../../etc/passwd").statusCode() == 200);
    }
}
