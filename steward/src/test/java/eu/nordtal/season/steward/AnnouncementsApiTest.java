package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.season.database.announce.AnnouncementBook;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.messages.document.Document;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Sends an admin's announcement draft through the routes, and retries the language the bot could not post. */
class AnnouncementsApiTest extends WebTestSupport {

    private static final Document HELLO = Document.decode("""
            {"parts": [{"type": "TEXT", "lines": [{"text": {"en": "Hello", "de": "Hallo"}}]}]}""");

    private static AnnouncementBook book() {
        return AnnouncementBook.using(WebFixture.postgres.dataSource());
    }

    private static Map<String, String> bothChannels() {
        final Map<String, String> channels = new LinkedHashMap<>();
        channels.put("de", "111");
        channels.put("en", "222");
        return channels;
    }

    private static JsonObject find(final String list, final long id) throws Exception {
        final HttpResponse<String> all = get("/api/announcements");
        assertEquals(200, all.statusCode(), all.body());
        final JsonArray announcements =
                GSON.fromJson(all.body(), JsonObject.class).getAsJsonArray(list);
        return announcements.asList().stream()
                .map(com.google.gson.JsonElement::getAsJsonObject)
                .filter(one -> one.get("id").getAsLong() == id)
                .findFirst()
                .orElseThrow(() -> new AssertionError(id + " is not among the " + list + ": " + all.body()));
    }

    private static List<String> deliveries(final JsonObject announcement) {
        return announcement.getAsJsonArray("targets").asList().stream()
                .map(target -> target.getAsJsonObject().get("language").getAsString() + " "
                        + target.getAsJsonObject().get("delivery").getAsString())
                .toList();
    }

    private static JsonObject line(final JsonObject ids, final String tag) throws Exception {
        return GSON.fromJson(get("/api/commands/" + ids.get(tag).getAsString()).body(), JsonObject.class);
    }

    @Test
    void aDraftWithAMalformedChannelOrLanguageIsRefused() throws Exception {
        final long before = count("SELECT count(*) FROM announcement");
        assertEquals(400, post("/api/announcements", "not json").statusCode());
        assertEquals(
                400,
                post("/api/announcements", "{\"document\": {\"parts\": []}, \"channels\": {\"EN x\": \"222\"}}")
                        .statusCode());
        assertEquals(
                400,
                post("/api/announcements", "{\"document\": {\"parts\": []}, \"channels\": {\"en\": \"#news\"}}")
                        .statusCode());
        assertEquals(before, count("SELECT count(*) FROM announcement"), "a refused draft was written");
    }

    @Test
    void aSendPostsEachChannelAndARetryPostsOnlyTheLanguageThatFailed() throws Exception {
        final long id = book().create(HELLO, bothChannels());
        assertEquals(List.of("de UNSENT", "en UNSENT"), deliveries(find("drafts", id)));

        final HttpResponse<String> sent = post("/api/announcements/" + id + "/send", "");
        assertEquals(202, sent.statusCode(), sent.body());
        final JsonObject ids = GSON.fromJson(sent.body(), JsonObject.class).getAsJsonObject("ids");
        assertEquals(2, ids.size(), sent.body());
        assertEquals("PENDING", line(ids, "en").get("status").getAsString());
        assertEquals(List.of("de SENDING", "en SENDING"), deliveries(find("sent", id)));
        assertEquals(409, put("/api/announcements/" + id, "{}").statusCode(), "a sent announcement no longer changes");
        assertEquals(409, delete(http, "/api/announcements/" + id).statusCode(), "a sent announcement stays");

        // Stands in for the bot: posts English and cannot post German.
        final long asked = Long.parseLong(ids.get("en").getAsString().replaceAll("\\D", ""));
        Inbox.over(WebFixture.postgres.dataSource(), BotRequest.TABLE)
                .drain(request -> request.id() == asked
                        ? Outcome.done(new BotRequest.PostAnnouncement.Answer(List.of(
                                new BotRequest.Announce.Posted("de", false),
                                new BotRequest.Announce.Posted("en", true))))
                        : Outcome.failed(null));
        book().posted(id, List.of("en"));
        assertEquals("DONE", line(ids, "en").get("status").getAsString());
        assertFalse(line(ids, "en").has("result"));
        final JsonObject german = line(ids, "de");
        assertEquals("FAILED", german.get("status").getAsString(), german.toString());
        assertEquals(
                "steward.said.not-announced",
                german.getAsJsonObject("result").get("key").getAsString());
        assertEquals(List.of("de NOT_POSTED", "en POSTED"), deliveries(find("sent", id)));

        final HttpResponse<String> retried = post("/api/announcements/" + id + "/send", "");
        assertEquals(202, retried.statusCode(), retried.body());
        assertEquals(
                List.of("de"),
                List.copyOf(GSON.fromJson(retried.body(), JsonObject.class)
                        .getAsJsonObject("ids")
                        .keySet()),
                "the retry posts German alone");
    }

    @Test
    void aDraftWithoutAChannelIsNotSentAndADraftIsDeleted() throws Exception {
        final long id = book().create(HELLO, Map.of());
        assertEquals(400, post("/api/announcements/" + id + "/send", "").statusCode());
        assertEquals(404, post("/api/announcements/999999/send", "").statusCode());
        assertEquals(204, delete(http, "/api/announcements/" + id).statusCode());
        assertEquals(404, delete(http, "/api/announcements/" + id).statusCode());
    }
}
