package eu.nordtal.season.database.mode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.mode.ModeStateStore.Container;
import eu.nordtal.season.database.mode.ModeStateStore.Mode;
import eu.nordtal.season.database.mode.ModeStateStore.Stack;
import eu.nordtal.season.database.mode.ModeStateStore.State;
import java.util.List;
import java.util.UUID;
import org.jdbi.v3.core.JdbiException;
import org.junit.jupiter.api.Test;

/** The Survival and Creative states of the players against PostgreSQL and the real migrations. */
class ModeStateStoreIntegrationTest {

    private static final PlayerId PLAYER = PlayerId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));

    /** In the order the store reads it back: by container name, then by slot. */
    private static final State SURVIVAL = new State(
            7,
            0.25f,
            List.of(
                    new Stack(Container.ENDER_CHEST, 26, "minecraft:diamond", 64, new byte[] {4}),
                    new Stack(Container.INVENTORY, 0, "minecraft:oak_log", 12, new byte[] {1, 2}),
                    new Stack(Container.INVENTORY, 40, "minecraft:shield", 1, new byte[] {3})));

    @Test
    void aModeLeftIsReturnedAsItWasWhenThePlayerComesBack() {
        final ModeStateStore store = ModeStateStore.using(TestDatabase.fresh().dataSourceAs(DatabaseRole.SMP));

        assertTrue(store.swap("smp", PLAYER, Mode.SURVIVAL, SURVIVAL, Mode.CREATIVE)
                .isEmpty());
        final State back = store.swap("smp", PLAYER, Mode.CREATIVE, new State(0, 0f, List.of()), Mode.SURVIVAL)
                .orElseThrow();

        assertEquals(7, back.level());
        assertEquals(0.25f, back.experience());
        assertEquals(3, back.stacks().size());
        for (int i = 0; i < 3; i++) {
            final Stack wrote = SURVIVAL.stacks().get(i);
            final Stack read = back.stacks().get(i);
            assertEquals(wrote.container(), read.container());
            assertEquals(wrote.slot(), read.slot());
            assertEquals(wrote.item(), read.item());
            assertEquals(wrote.amount(), read.amount());
            assertArrayEquals(wrote.data(), read.data());
        }
        assertEquals(
                List.of(),
                store.swap("smp", PLAYER, Mode.SURVIVAL, SURVIVAL, Mode.CREATIVE)
                        .orElseThrow()
                        .stacks(),
                "an empty Creative state stays empty");
    }

    @Test
    void aStateLeftAgainReplacesTheOneBeforeAndStaysOnItsServer() {
        final TestDatabase database = TestDatabase.fresh();
        final ModeStateStore smp = ModeStateStore.using(database.dataSourceAs(DatabaseRole.SMP));
        smp.swap("smp", PLAYER, Mode.SURVIVAL, SURVIVAL, Mode.CREATIVE);
        final State fewer =
                new State(1, 0f, List.of(new Stack(Container.INVENTORY, 3, "minecraft:dirt", 1, new byte[] {5})));
        smp.swap("smp", PLAYER, Mode.SURVIVAL, fewer, Mode.CREATIVE);

        final State back = smp.swap("smp", PLAYER, Mode.CREATIVE, new State(0, 0f, List.of()), Mode.SURVIVAL)
                .orElseThrow();
        assertEquals(
                List.of("minecraft:dirt"),
                back.stacks().stream().map(Stack::item).toList());

        final ModeStateStore hungerGames = ModeStateStore.using(database.dataSourceAs(DatabaseRole.HUNGER_GAMES));
        assertTrue(hungerGames
                .swap("hunger-games", PLAYER, Mode.CREATIVE, new State(0, 0f, List.of()), Mode.SURVIVAL)
                .isEmpty());
    }

    @Test
    void aSwapThatFailsStoresNothing() {
        final ModeStateStore store = ModeStateStore.using(TestDatabase.fresh().dataSourceAs(DatabaseRole.SMP));
        store.swap("smp", PLAYER, Mode.SURVIVAL, SURVIVAL, Mode.CREATIVE);
        final State broken =
                new State(0, 0f, List.of(new Stack(Container.INVENTORY, 1, "minecraft:dirt", 0, new byte[] {6})));

        assertThrows(JdbiException.class, () -> store.swap("smp", PLAYER, Mode.SURVIVAL, broken, Mode.CREATIVE));

        final State back = store.swap("smp", PLAYER, Mode.CREATIVE, new State(0, 0f, List.of()), Mode.SURVIVAL)
                .orElseThrow();
        assertEquals(3, back.stacks().size());
    }
}
