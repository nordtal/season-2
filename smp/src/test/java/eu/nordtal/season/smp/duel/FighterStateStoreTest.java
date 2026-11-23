package eu.nordtal.season.smp.duel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.smp.duel.Duels.JoinRecovery;
import eu.nordtal.season.smp.duel.FighterStateStore.StoredEffect;
import eu.nordtal.season.smp.duel.FighterStateStore.StoredLocation;
import eu.nordtal.season.smp.duel.FighterStateStore.StoredState;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FighterStateStoreTest {

    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID BYSTANDER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final World NORDTAL = fakeWorld("nordtal");
    private static final Location EXIT = new Location(NORDTAL, 12.5, 65.0, -4.5, 90f, 0f);
    private static final Location ARENA = new Location(NORDTAL, 100.5, 70.0, 200.5, 0f, 0f);
    private static final Logger LOGGER = Logger.getLogger(FighterStateStoreTest.class.getName());

    private static final class StubItem extends ItemStack {
        private final String label;

        private StubItem(final String label) {
            this.label = label;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }
    }

    private static final class FighterSnapshot {
        List<@Nullable String> inventory = new ArrayList<>();
        List<@Nullable String> armour = new ArrayList<>();
        double health = 20.0;
        int foodLevel = 20;
        float saturation = 20f;
        int level;
        float experience;
        String gameMode = GameMode.SURVIVAL.name();
        List<StoredEffect> effects = new ArrayList<>();

        @Nullable
        Location location = ARENA;

        void equipKit() {
            inventory = new ArrayList<>(List.of("kit-sword", "kit-gapple"));
            armour = new ArrayList<>(List.of("kit-boots", "kit-leggings", "kit-chestplate", "kit-helmet"));
            health = 20.0;
            foodLevel = 20;
            saturation = 20f;
            level = 0;
            experience = 0f;
            gameMode = GameMode.SURVIVAL.name();
            effects = new ArrayList<>(List.of(new StoredEffect("minecraft:speed", 100, 1, false, true, true)));
            location = ARENA;
        }

        void applyRestore(final SavedState state, final @Nullable Location exit) {
            final StoredState restored = encode(state);
            inventory = new ArrayList<>(restored.inventory());
            armour = new ArrayList<>(restored.armour());
            health = restored.health();
            foodLevel = restored.foodLevel();
            saturation = restored.saturation();
            level = restored.level();
            experience = restored.experience();
            gameMode = restored.gameMode();
            effects = new ArrayList<>(restored.effects());
            location = state.targetLocation(exit);
        }
    }

    @Test
    void savedStateRoundTripsEveryFieldThroughDisk(@TempDir final Path dir) {
        final Location originalLocation = new Location(NORDTAL, 1.25, 64.0, 2.75, 135f, -15f);
        final SavedState original = new SavedState(
                originalLocation,
                new @Nullable ItemStack[] {new StubItem("pickaxe"), null, new StubItem("bread")},
                new @Nullable ItemStack[] {new StubItem("boots"), null, new StubItem("chestplate"), null},
                16.5,
                17,
                4.25f,
                27,
                0.4f,
                GameMode.ADVENTURE,
                List.of(new StoredEffect("minecraft:night_vision", 600, 1, true, false, true)));
        final StoredState encoded = encode(original);

        final FighterStateStore store = new FighterStateStore(dir, LOGGER);
        assertTrue(store.save(FIRST, encoded));

        final StoredState loaded = new FighterStateStore(dir, LOGGER).load(FIRST);
        assertNotNull(loaded);
        final SavedState restored = decode(loaded);

        assertEquals(encoded, encode(restored));
        assertEquals(originalLocation, restored.targetLocation(null));
        assertEquals(EXIT, restored.targetLocation(EXIT));
    }

    @Test
    void fromStoredFallsBackOnUnknownGameModeAndSkipsInvalidEffectKeys() {
        final StoredState malformed = new StoredState(
                new StoredLocation("nordtal", 1.0, 64.0, 2.0, 0f, 0f),
                List.of("pickaxe"),
                List.of(),
                18.0,
                19,
                4.0f,
                5,
                0.5f,
                "UNKNOWN_MODE",
                List.of(
                        new StoredEffect("not valid key!", 100, 0, false, true, true),
                        new StoredEffect("minecraft:not_an_effect", 150, 0, false, true, true),
                        new StoredEffect("minecraft:speed", 200, 1, false, true, true)));

        final StoredState decoded = encode(decode(malformed));

        assertEquals(GameMode.SURVIVAL.name(), decoded.gameMode());
        assertEquals(List.of(new StoredEffect("minecraft:speed", 200, 1, false, true, true)), decoded.effects());
    }

    @Test
    void joinRecoveryDecisionSkipsParksOrRestoresByState() {
        assertEquals(JoinRecovery.SKIP, JoinRecovery.of(false, false, false, false));
        assertEquals(JoinRecovery.SKIP, JoinRecovery.of(true, false, false, true));
        assertEquals(JoinRecovery.SKIP, JoinRecovery.of(false, true, false, true));
        assertEquals(JoinRecovery.PARK_PENDING, JoinRecovery.of(false, false, true, true));
        assertEquals(JoinRecovery.RESTORE_NOW, JoinRecovery.of(false, false, false, true));
    }

    @Test
    void aSimulatedMidDuelCrashRestoresEachFightersOwnStateOnceAtExitWithNoKitItemsLeft(@TempDir final Path dir) {
        final SavedState firstSaved = firstSavedState();
        final SavedState secondSaved = secondSavedState();
        final FighterStateStore beforeCrash = new FighterStateStore(dir, LOGGER);
        assertTrue(beforeCrash.save(FIRST, encode(firstSaved)));
        assertTrue(beforeCrash.save(SECOND, encode(secondSaved)));

        final FighterSnapshot first = new FighterSnapshot();
        final FighterSnapshot second = new FighterSnapshot();
        first.equipKit();
        second.equipKit();

        // Unclean crash: beforeCrash is discarded without stop() or delete().
        final FighterStateStore afterCrash = new FighterStateStore(dir, LOGGER);
        final Map<UUID, SavedState> pending = new HashMap<>();

        assertEquals(JoinRecovery.RESTORE_NOW, joinAlive(FIRST, afterCrash, pending, first));
        assertRestoredAtExit(encode(firstSaved), first);
        assertNull(afterCrash.load(FIRST));

        // A second join is a no-op and does not overwrite or teleport again.
        first.inventory = new ArrayList<>(List.of("new-item"));
        first.location = new Location(NORDTAL, 50.0, 70.0, 50.0, 0f, 0f);
        assertEquals(JoinRecovery.SKIP, joinAlive(FIRST, afterCrash, pending, first));
        assertEquals(List.of("new-item"), first.inventory);
        assertEquals(new Location(NORDTAL, 50.0, 70.0, 50.0, 0f, 0f), first.location);

        // Even after another restart, the second fighter still gets their own state once at exit.
        final FighterStateStore afterSecondRestart = new FighterStateStore(dir, LOGGER);
        assertEquals(JoinRecovery.RESTORE_NOW, joinAlive(SECOND, afterSecondRestart, pending, second));
        assertRestoredAtExit(encode(secondSaved), second);
        assertNull(afterSecondRestart.load(SECOND));
        assertEquals(JoinRecovery.SKIP, joinAlive(SECOND, afterSecondRestart, pending, second));
    }

    @Test
    void aDeadJoinParksPendingUntilRespawnRestoresAndDeletesTheFile(@TempDir final Path dir) {
        final SavedState saved = firstSavedState();
        final FighterStateStore store = new FighterStateStore(dir, LOGGER);
        assertTrue(store.save(FIRST, encode(saved)));

        final FighterSnapshot fighter = new FighterSnapshot();
        fighter.equipKit();
        final Map<UUID, SavedState> pending = new HashMap<>();
        final AtomicInteger respawns = new AtomicInteger();

        assertEquals(
                JoinRecovery.PARK_PENDING,
                Duels.recoverOnJoin(
                        FIRST,
                        false,
                        true,
                        store,
                        pending,
                        FighterStateStoreTest::decode,
                        state -> fighter.applyRestore(state, EXIT),
                        respawns::incrementAndGet));
        assertEquals(1, respawns.get());
        assertTrue(pending.containsKey(FIRST));
        assertNotNull(store.load(FIRST));
        assertEquals(ARENA, fighter.location);

        // Duplicate join while pending is a no-op.
        assertEquals(
                JoinRecovery.SKIP,
                Duels.recoverOnJoin(
                        FIRST,
                        false,
                        true,
                        store,
                        pending,
                        FighterStateStoreTest::decode,
                        state -> fighter.applyRestore(state, EXIT),
                        respawns::incrementAndGet));
        assertEquals(1, respawns.get());

        // Respawn consumes pending, restores at exit, and deletes the file.
        fighter.applyRestore(Objects.requireNonNull(pending.remove(FIRST)), EXIT);
        store.delete(FIRST);

        assertRestoredAtExit(encode(saved), fighter);
        assertNull(store.load(FIRST));
        assertEquals(JoinRecovery.SKIP, joinAlive(FIRST, store, pending, fighter));
    }

    @Test
    void aCleanShutdownLeavesNoPersistedStateForNextJoin(@TempDir final Path dir) {
        final FighterStateStore running = new FighterStateStore(dir, LOGGER);
        assertTrue(running.save(FIRST, encode(firstSavedState())));
        running.delete(FIRST);

        final FighterStateStore restarted = new FighterStateStore(dir, LOGGER);
        assertNull(restarted.load(FIRST));
        assertEquals(JoinRecovery.SKIP, joinAlive(FIRST, restarted, new HashMap<>(), new FighterSnapshot()));
    }

    @Test
    void anUnrestoredSnapshotIsNotOverwrittenByANewSave(@TempDir final Path dir) {
        final StoredState original = encode(firstSavedState());
        final StoredState overwrite = encode(secondSavedState());
        final FighterStateStore store = new FighterStateStore(dir, LOGGER);
        assertTrue(store.save(FIRST, original));
        assertFalse(store.save(FIRST, overwrite));
        assertEquals(original, store.load(FIRST));
    }

    @Test
    void aNonFighterJoiningAfterACrashIsUntouched(@TempDir final Path dir) {
        final FighterStateStore beforeCrash = new FighterStateStore(dir, LOGGER);
        assertTrue(beforeCrash.save(FIRST, encode(firstSavedState())));

        final FighterStateStore afterCrash = new FighterStateStore(dir, LOGGER);
        assertEquals(JoinRecovery.SKIP, joinAlive(BYSTANDER, afterCrash, new HashMap<>(), new FighterSnapshot()));
    }

    private static JoinRecovery joinAlive(
            final UUID playerId,
            final FighterStateStore store,
            final Map<UUID, SavedState> pending,
            final FighterSnapshot fighter) {
        return Duels.recoverOnJoin(
                playerId,
                false,
                false,
                store,
                pending,
                FighterStateStoreTest::decode,
                state -> fighter.applyRestore(state, EXIT),
                () -> {});
    }

    private static void assertRestoredAtExit(final StoredState expected, final FighterSnapshot actual) {
        assertEquals(expected.inventory(), actual.inventory);
        assertEquals(expected.armour(), actual.armour);
        assertEquals(expected.health(), actual.health);
        assertEquals(expected.foodLevel(), actual.foodLevel);
        assertEquals(expected.saturation(), actual.saturation);
        assertEquals(expected.level(), actual.level);
        assertEquals(expected.experience(), actual.experience);
        assertEquals(expected.gameMode(), actual.gameMode);
        assertEquals(expected.effects(), actual.effects);
        assertEquals(EXIT, actual.location);
        assertFalse(actual.inventory.contains("kit-sword"));
    }

    private static SavedState firstSavedState() {
        return new SavedState(
                new Location(NORDTAL, 1.0, 64.0, 2.0, 0f, 0f),
                new @Nullable ItemStack[] {new StubItem("first-pickaxe"), null, new StubItem("first-bread")},
                new @Nullable ItemStack[] {new StubItem("first-boots"), null, new StubItem("first-chest"), null},
                18.5,
                19,
                4.5f,
                27,
                0.4f,
                GameMode.SURVIVAL,
                List.of(new StoredEffect("minecraft:night_vision", 600, 0, false, false, true)));
    }

    private static SavedState secondSavedState() {
        return new SavedState(
                new Location(NORDTAL, 3.0, 64.0, 4.0, 180f, 0f),
                new @Nullable ItemStack[] {null, new StubItem("second-axe")},
                new @Nullable ItemStack[] {null, new StubItem("second-leggings"), null, new StubItem("second-helmet")},
                20.0,
                20,
                5.0f,
                9,
                0.75f,
                GameMode.SURVIVAL,
                List.of(new StoredEffect("minecraft:fire_resistance", 1200, 0, true, true, true)));
    }

    private static StoredState encode(final SavedState state) {
        return state.toStored(item -> ((StubItem) item).label);
    }

    private static SavedState decode(final StoredState stored) {
        return SavedState.fromStored(
                stored,
                name -> NORDTAL.getName().equals(name) ? NORDTAL : null,
                StubItem::new,
                key -> java.util.Set.of("minecraft:speed", "minecraft:night_vision", "minecraft:fire_resistance")
                        .contains(key.asString()),
                LOGGER);
    }

    private static World fakeWorld(final String name) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[] {World.class},
                (_, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "equals" -> args[0] instanceof final World other && name.equals(other.getName());
                    case "hashCode" -> name.hashCode();
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
