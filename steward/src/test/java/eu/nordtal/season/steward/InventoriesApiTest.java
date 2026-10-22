package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.database.inbox.SmpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Tests the inventories of a player as Steward shows them and removes stacks from them. */
class InventoriesApiTest extends WebTestSupport {

    private static final String PERSON = "700000000000000051";
    private static final String PLAYER = "00000000-0000-0000-0000-0000000000b1";

    @Test
    void aStackIsRemovedFromAStoredStateOnceAndAHeldOneIsTheServers() throws Exception {
        sql("INSERT INTO discord_user (discord_id) VALUES ('" + PERSON + "')");
        try {
            final JsonObject unlinked = GSON.fromJson(get(inventories()).body(), JsonObject.class);
            assertFalse(unlinked.get("linked").getAsBoolean());
            link();
            state("smp", "SURVIVAL", "STORED");
            state("limbo", "SURVIVAL", "HELD");

            final HttpResponse<String> read = get(inventories());
            assertEquals(200, read.statusCode(), read.body());
            final JsonObject shown = GSON.fromJson(read.body(), JsonObject.class);
            assertTrue(shown.get("linked").getAsBoolean());
            assertFalse(shown.has("online"), "the player is on no server");
            assertEquals(2, shown.getAsJsonArray("states").size());
            final JsonObject smp = shown.getAsJsonArray("states").get(1).getAsJsonObject();
            assertEquals("smp", smp.get("server").getAsString());
            assertEquals("STORED", smp.get("status").getAsString());
            assertEquals(7, smp.get("level").getAsInt());
            assertEquals(
                    List.of("INVENTORY 36 minecraft:iron_helmet 1", "ENDER_CHEST 3 minecraft:oak_log 12"),
                    smp.getAsJsonArray("stacks").asList().stream()
                            .map(stack -> stack.getAsJsonObject())
                            .map(stack -> stack.get("container").getAsString() + " "
                                    + stack.get("slot").getAsInt() + " "
                                    + stack.get("item").getAsString() + " "
                                    + stack.get("amount").getAsInt())
                            .sorted(java.util.Comparator.reverseOrder())
                            .toList());

            assertEquals(
                    204,
                    remove("smp", "ENDER_CHEST", 3, "minecraft:oak_log", 12).statusCode());
            assertEquals(
                    0,
                    count("SELECT count(*) FROM game_mode_state_item WHERE player_uuid = '" + PLAYER
                            + "' AND server = 'smp' AND container = 'ENDER_CHEST'"));
            assertEquals(
                    1,
                    count("SELECT count(*) FROM journal_entry e JOIN journal_removed_stack r ON r.entry = e.id"
                            + " WHERE e.subject = '" + PERSON + "' AND e.action = 'REMOVE_STACK'"
                            + " AND r.item = 'minecraft:oak_log' AND r.amount = 12"));

            final HttpResponse<String> again = remove("smp", "ENDER_CHEST", 3, "minecraft:oak_log", 12);
            assertEquals(409, again.statusCode(), again.body());
            assertTrue(again.body().contains("STACK_CHANGED"), again.body());
            final HttpResponse<String> other = remove("smp", "INVENTORY", 36, "minecraft:iron_helmet", 2);
            assertEquals(409, other.statusCode(), "the slot holds another amount");
            final HttpResponse<String> limbo = remove("limbo", "INVENTORY", 36, "minecraft:iron_helmet", 1);
            assertEquals(409, limbo.statusCode(), "limbo holds its state and takes no request");
            assertEquals(
                    1,
                    count("SELECT count(*) FROM journal_entry WHERE subject = '" + PERSON + "'"),
                    "a refused removal is not journaled");
        } finally {
            forget();
        }
    }

    @Test
    void theServerThatHoldsTheStateIsAskedAndItsRefusalIsShown() throws Exception {
        sql("INSERT INTO discord_user (discord_id) VALUES ('" + PERSON + "')");
        try {
            link();
            state("smp", "SURVIVAL", "HELD");
            sql("INSERT INTO online_player (mc_uuid, mc_name, subject, updated) VALUES ('" + PLAYER
                    + "', 'Bea', 'smp', now())");
            final Inbox<SmpRequest> smp = Inbox.over(WebFixture.postgres.dataSource(), SmpRequest.TABLE);

            final CompletableFuture<HttpResponse<String>> read =
                    CompletableFuture.supplyAsync(() -> call(inventories()));
            assertTrue(answer(smp, SmpRequest.StoreModeState.class, Outcome.done(null)));
            assertEquals(200, read.get().statusCode(), read.get().body());
            assertEquals(
                    "smp",
                    GSON.fromJson(read.get().body(), JsonObject.class)
                            .get("online")
                            .getAsString());

            final CompletableFuture<HttpResponse<String>> removed = CompletableFuture.supplyAsync(() -> {
                try {
                    return remove("smp", "INVENTORY", 36, "minecraft:iron_helmet", 1);
                } catch (final Exception failed) {
                    throw new IllegalStateException(failed);
                }
            });
            assertTrue(answer(smp, SmpRequest.RemoveStack.class, Outcome.refused(ServerRefusal.STACK_CHANGED.with())));
            assertEquals(409, removed.get().statusCode(), removed.get().body());
            assertTrue(
                    removed.get().body().contains("STACK_CHANGED"),
                    removed.get().body());
            assertEquals(
                    1,
                    count("SELECT count(*) FROM game_mode_state_item WHERE player_uuid = '" + PLAYER + "'"
                            + " AND server = 'smp' AND slot = 36"),
                    "Steward wrote into a state the server holds");
        } finally {
            sql("DELETE FROM online_player WHERE mc_uuid = '" + PLAYER + "'");
            forget();
        }
    }

    /** Claims requests the way the server does and settles the first of {@code kind} with {@code outcome}. */
    private static boolean answer(final Inbox<SmpRequest> smp, final Class<?> kind, final Outcome outcome)
            throws Exception {
        for (int tries = 0; tries < 100; tries++) {
            final Optional<Request<SmpRequest>> claimed = smp.claim();
            if (claimed.isEmpty()) {
                Thread.sleep(20);
            } else if (kind.isInstance(claimed.get().payload())) {
                smp.settle(claimed.get().id(), outcome);
                return true;
            } else {
                // A request another test left pending; no server answers it.
                smp.settle(claimed.get().id(), Outcome.failed(null));
            }
        }
        return false;
    }

    private static HttpResponse<String> call(final String path) {
        try {
            return get(path);
        } catch (final Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static String inventories() {
        return "/api/people/" + PERSON + "/inventories";
    }

    private static HttpResponse<String> remove(
            final String server, final String container, final int slot, final String item, final int amount)
            throws Exception {
        return post(
                inventories() + "/remove",
                "{\"server\": \"" + server + "\", \"mode\": \"SURVIVAL\", \"container\": \"" + container
                        + "\", \"slot\": " + slot + ", \"item\": \"" + item + "\", \"amount\": " + amount + "}");
    }

    private static void link() throws Exception {
        sql("INSERT INTO account_link (discord_id, mc_uuid, mc_name) VALUES ('" + PERSON + "', '" + PLAYER
                + "', 'Bea')");
    }

    private static void state(final String server, final String mode, final String status) throws Exception {
        sql("INSERT INTO game_mode_state (server, player_uuid, mode, level, experience, saved, status) VALUES ('"
                + server + "', '" + PLAYER + "', '" + mode + "', 7, 0.5, now(), '" + status + "')");
        sql("INSERT INTO game_mode_state_item (server, player_uuid, mode, container, slot, item, amount, data)"
                + " VALUES ('" + server + "', '" + PLAYER + "', '" + mode
                + "', 'INVENTORY', 36, 'minecraft:iron_helmet', 1, '\\x00'), ('" + server + "', '" + PLAYER
                + "', '" + mode + "', 'ENDER_CHEST', 3, 'minecraft:oak_log', 12, '\\x00')");
    }

    private static void forget() throws Exception {
        sql("DELETE FROM game_mode_state WHERE player_uuid = '" + PLAYER + "'");
        sql("DELETE FROM smp_inbox WHERE id IN (SELECT request FROM smp_store_mode_state WHERE player = '" + PLAYER
                + "' UNION SELECT request FROM smp_remove_stack WHERE player = '" + PLAYER + "')");
        sql("DELETE FROM journal_entry WHERE subject = '" + PERSON + "'");
        sql("DELETE FROM account_link WHERE discord_id = '" + PERSON + "'");
        sql("DELETE FROM discord_user WHERE discord_id = '" + PERSON + "'");
    }
}
