package eu.nordtal.season.database.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Publishes the menus a dialogue may open. */
class NpcMenuStoreIntegrationTest {

    @Test
    void aServerReplacesItsOwnMenusAndStewardReadsEveryKey() {
        final TestDatabase database = TestDatabase.fresh();
        final NpcMenuStore smp = NpcMenuStore.using(database.dataSourceAs(DatabaseRole.SMP));
        smp.publish(
                "smp",
                List.of(
                        new NpcMenu("wheel", new Dialogue.Words("Open wheel", "Rad öffnen")),
                        new NpcMenu("objectives", new Dialogue.Words("Open objectives", "Ziele öffnen"))));
        smp.publish("smp", List.of(new NpcMenu("objectives", new Dialogue.Words("Objectives", "Ziele"))));

        assertEquals(
                List.of(new NpcMenu("objectives", new Dialogue.Words("Objectives", "Ziele"))),
                NpcMenuStore.using(database.dataSourceAs(DatabaseRole.STEWARD)).menus());
    }
}
