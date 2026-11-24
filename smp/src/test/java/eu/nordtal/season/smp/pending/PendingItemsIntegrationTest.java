package eu.nordtal.season.smp.pending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.TestDatabase;
import org.junit.jupiter.api.Test;

class PendingItemsIntegrationTest {

    @Test
    void anOfflinePrizeKeepsItsItemAndCanBeClaimedOnce() {
        final TestDatabase database = TestDatabase.fresh();
        final DiscordId player = DiscordId.of("100000000000000042");
        final String item = "minecraft:diamond";
        final String data = "AQID";
        Jdbis.over(database.dataSource())
                .useHandle(handle -> handle.createUpdate("INSERT INTO discord_user (discord_id) VALUES (:id)")
                        .bind("id", player)
                        .execute());

        final var jdbi = Jdbis.over(database.dataSourceAs(DatabaseRole.SMP));
        jdbi.onDemand(PendingItems.class).store(player, item, 3, data);

        final PendingItems pending = jdbi.onDemand(PendingItems.class);
        assertTrue(pending.waitsFor(player));
        final var claimed = pending.claim(player);
        assertEquals(1, claimed.size());
        assertEquals(item, claimed.getFirst().item());
        assertEquals(3, claimed.getFirst().amount());
        assertEquals(data, claimed.getFirst().data());
        assertTrue(pending.claim(player).isEmpty());
        assertFalse(pending.waitsFor(player));
    }

    @Test
    void tunnelMinerPickaxeAndEnchantedBookPreserveEncodedDataThroughPendingItems() {
        final TestDatabase database = TestDatabase.fresh();
        final DiscordId player = DiscordId.of("100000000000000043");
        final String bookData = "H4sIAAAAAAAA/2NgYGBgYGRgAAAt8a3iCAAAAA==";
        final String pickData = "H4sIAAAAAAAA/2NgYGBgYGRkAAAxuW3jCAAAAA==";
        Jdbis.over(database.dataSource())
                .useHandle(handle -> handle.createUpdate("INSERT INTO discord_user (discord_id) VALUES (:id)")
                        .bind("id", player)
                        .execute());

        final var jdbi = Jdbis.over(database.dataSourceAs(DatabaseRole.SMP));
        final PendingItems pending = jdbi.onDemand(PendingItems.class);
        pending.store(player, "minecraft:enchanted_book", 1, bookData);
        pending.store(player, "minecraft:diamond_pickaxe", 1, pickData);

        final var claimed = pending.claim(player);
        assertEquals(2, claimed.size());
        assertEquals("minecraft:enchanted_book", claimed.get(0).item());
        assertEquals(bookData, claimed.get(0).data());
        assertEquals("minecraft:diamond_pickaxe", claimed.get(1).item());
        assertEquals(pickData, claimed.get(1).data());
    }
}
