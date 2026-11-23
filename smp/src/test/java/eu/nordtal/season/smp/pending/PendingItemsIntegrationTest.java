package eu.nordtal.season.smp.wheel;

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
}
