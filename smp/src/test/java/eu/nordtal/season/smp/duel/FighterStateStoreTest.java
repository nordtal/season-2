package eu.nordtal.season.smp.duel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.smp.duel.FighterStateStore.StoredEffect;
import eu.nordtal.season.smp.duel.FighterStateStore.StoredLocation;
import eu.nordtal.season.smp.duel.FighterStateStore.StoredState;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FighterStateStoreTest {

    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID BYSTANDER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final StoredLocation EXIT = new StoredLocation("nordtal", 12.5, 65.0, -4.5, 90f, 0f);
    private static final StoredLocation ARENA = new StoredLocation("nordtal", 100.5, 70.0, 200.5, 0f, 0f);
    private static final Logger LOGGER = Logger.getLogger(FighterStateStoreTest.class.getName());

    private static final class Fighter {
        List<String> inventory = new ArrayList<>();
        List<String> armour = new ArrayList<>();
        int level;
        float experience;
        List<StoredEffect> effects = new ArrayList<>();
        StoredLocation location = ARENA;

        void equipKit() {
            inventory = new ArrayList<>(List.of("kit-sword", "kit-gapple"));
            armour = new ArrayList<>(List.of("kit-boots", "kit-leggings", "kit-chestplate", "kit-helmet"));
            level = 0;
            experience = 0f;
            effects = new ArrayList<>(List.of(new StoredEffect("minecraft:speed", 100, 1, false, true, true)));
            location = ARENA;
        }

        void applyRestore(final StoredState state, final StoredLocation exit) {
            inventory = new ArrayList<>(state.inventory());
            armour = new ArrayList<>(state.armour());
            level = state.level();
            experience = state.experience();
            effects = new ArrayList<>(state.effects());
            location = exit;
        }
    }

    @Test
    void theDuelListenerHandlesPlayerJoinForCrashRestores() throws NoSuchMethodException {
        final EventHandler handler =
                DuelListener.class.getMethod("onJoin", PlayerJoinEvent.class).getAnnotation(EventHandler.class);

        assertNotNull(handler);
    }

    @Test
    void aSimulatedMidDuelCrashRestoresEachFightersOwnStateOnceAtExitWithNoKitItemsLeft(@TempDir final Path dir) {
        final StoredState firstSaved = firstSavedState();
        final StoredState secondSaved = secondSavedState();

        final FighterStateStore beforeCrash = new FighterStateStore(dir, LOGGER);
        assertTrue(beforeCrash.save(FIRST, firstSaved));
        assertTrue(beforeCrash.save(SECOND, secondSaved));

        final Fighter first = new Fighter();
        final Fighter second = new Fighter();
        first.equipKit();
        second.equipKit();

        // Unclean crash: beforeCrash is discarded without stop() or delete().
        final FighterStateStore afterCrash = new FighterStateStore(dir, LOGGER);
        assertTrue(afterCrash.has(FIRST));
        assertTrue(afterCrash.has(SECOND));

        assertTrue(afterCrash.restoreOnce(FIRST, state -> first.applyRestore(state, EXIT)));
        assertRestored(firstSaved, first);
        assertFalse(afterCrash.has(FIRST));

        // A second join for the first fighter does not overwrite or teleport again.
        first.inventory = new ArrayList<>(List.of("new-item"));
        first.location = new StoredLocation("nordtal", 50.0, 70.0, 50.0, 0f, 0f);
        assertFalse(afterCrash.restoreOnce(FIRST, state -> first.applyRestore(state, EXIT)));
        assertEquals(List.of("new-item"), first.inventory);

        // Even after another restart, the second fighter still gets their own state once at exit.
        final FighterStateStore afterSecondRestart = new FighterStateStore(dir, LOGGER);
        assertTrue(afterSecondRestart.restoreOnce(SECOND, state -> second.applyRestore(state, EXIT)));
        assertRestored(secondSaved, second);
        assertFalse(afterSecondRestart.has(SECOND));
        assertFalse(afterSecondRestart.restoreOnce(SECOND, state -> second.applyRestore(state, EXIT)));
    }

    private static StoredState firstSavedState() {
        return new StoredState(
                new StoredLocation("nordtal", 1.0, 64.0, 2.0, 0f, 0f),
                Arrays.asList("first-pickaxe", null, "first-bread"),
                Arrays.asList("first-boots", null, "first-chest", null),
                18.5,
                19,
                4.5f,
                27,
                0.4f,
                "SURVIVAL",
                List.of(new StoredEffect("minecraft:night_vision", 600, 0, false, false, true)));
    }

    private static StoredState secondSavedState() {
        return new StoredState(
                new StoredLocation("nordtal", 3.0, 64.0, 4.0, 180f, 0f),
                Arrays.asList(null, "second-axe"),
                Arrays.asList(null, "second-leggings", null, "second-helmet"),
                20.0,
                20,
                5.0f,
                9,
                0.75f,
                "SURVIVAL",
                List.of(new StoredEffect("minecraft:fire_resistance", 1200, 0, true, true, true)));
    }

    private static void assertRestored(final StoredState expected, final Fighter actual) {
        assertEquals(expected.inventory(), actual.inventory);
        assertEquals(expected.armour(), actual.armour);
        assertEquals(expected.level(), actual.level);
        assertEquals(expected.experience(), actual.experience);
        assertEquals(expected.effects(), actual.effects);
        assertEquals(EXIT, actual.location);
        assertFalse(actual.inventory.contains("kit-sword"));
    }

    @Test
    void aCleanShutdownLeavesNoPersistedStateForNextJoin(@TempDir final Path dir) {
        final StoredState saved =
                new StoredState(EXIT, List.of("item"), List.of(), 20.0, 20, 5.0f, 3, 0.2f, "SURVIVAL", List.of());
        final FighterStateStore running = new FighterStateStore(dir, LOGGER);
        assertTrue(running.save(FIRST, saved));
        running.delete(FIRST);

        final FighterStateStore restarted = new FighterStateStore(dir, LOGGER);
        assertFalse(restarted.has(FIRST));
        assertFalse(restarted.restoreOnce(FIRST, state -> {}));
    }

    @Test
    void anUnrestoredSnapshotIsNotOverwrittenByANewSave(@TempDir final Path dir) {
        final StoredState original =
                new StoredState(EXIT, List.of("original"), List.of(), 20.0, 20, 5.0f, 10, 0.0f, "SURVIVAL", List.of());
        final StoredState overwrite =
                new StoredState(ARENA, List.of("kit-sword"), List.of(), 20.0, 20, 5.0f, 0, 0.0f, "SURVIVAL", List.of());
        final FighterStateStore store = new FighterStateStore(dir, LOGGER);
        assertTrue(store.save(FIRST, original));
        assertFalse(store.save(FIRST, overwrite));
        assertEquals(original, store.load(FIRST));
    }

    @Test
    void aNonFighterJoiningAfterACrashIsUntouched(@TempDir final Path dir) {
        final StoredState saved =
                new StoredState(EXIT, List.of("item"), List.of(), 20.0, 20, 5.0f, 3, 0.2f, "SURVIVAL", List.of());
        final FighterStateStore beforeCrash = new FighterStateStore(dir, LOGGER);
        assertTrue(beforeCrash.save(FIRST, saved));

        final FighterStateStore afterCrash = new FighterStateStore(dir, LOGGER);
        assertFalse(afterCrash.restoreOnce(BYSTANDER, state -> {}));
    }
}
