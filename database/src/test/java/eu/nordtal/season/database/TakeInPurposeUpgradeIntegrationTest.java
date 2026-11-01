package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.database.inbox.TakeInPurpose;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/** Holds that a take-in asked before it named its purpose reads back as a wheel prize, which every take-in was. */
class TakeInPurposeUpgradeIntegrationTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Test
    void aTakeInAskedBeforeItsPurposeIsAWheelPrize() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "105");
        Jdbi.create(upgraded.dataSource()).useHandle(handle -> handle.execute("""
                INSERT INTO smp_inbox (id, kind, actor_kind, status) VALUES (1, 'TAKE_IN_ITEM', 'STEWARD', 'RUNNING');
                INSERT INTO smp_take_in_item (request, player) VALUES (1, '00000000-0000-0000-0000-00000000000a');
                """));

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "106");

        assertEquals(
                new SmpRequest.TakeInItem(PlayerId.of(ALEX), TakeInPurpose.WHEEL_PRIZE),
                Inbox.over(upgraded.dataSource(), SmpRequest.TABLE)
                        .find(1)
                        .orElseThrow()
                        .payload());
    }
}
