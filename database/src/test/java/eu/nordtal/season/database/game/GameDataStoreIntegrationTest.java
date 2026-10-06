package eu.nordtal.season.database.game;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.Test;

/** The servers' game data and the icons against a real PostgreSQL and the real migrations; skipped without Docker. */
class GameDataStoreIntegrationTest {

    private static final GameCatalogue CATALOGUE = new GameCatalogue(
            "26.2",
            List.of("vanilla", "file/nordtal"),
            Map.of(
                    "item",
                    List.of(GameCatalogue.Entry.named("minecraft:oak_log", "block.minecraft.oak_log", "Oak Log")),
                    "advancement",
                    List.of(new GameCatalogue.Entry(
                            "minecraft:story/mine_stone",
                            "advancements.story.mine_stone.title",
                            "Stone Age",
                            "minecraft:story/root",
                            "Mine Stone with your new Pickaxe",
                            "task",
                            "minecraft:wooden_pickaxe",
                            false,
                            null))),
            Map.of("item", List.of(new GameCatalogue.Tag("minecraft:logs", List.of("minecraft:oak_log")))));

    @Test
    void aServersCatalogueReadsBackAsItWasWrittenAndAnotherExportReplacesIt() {
        final TestDatabase database = TestDatabase.fresh();
        final GameDataStore smp = GameDataStore.using(database.dataSourceAs(DatabaseRole.SMP));
        smp.publish("smp", new GameCatalogue("26.1", List.of(), Map.of(), Map.of()));
        smp.publish("smp", CATALOGUE);

        final GameDataStore steward = GameDataStore.using(database.dataSourceAs(DatabaseRole.STEWARD));
        assertEquals(Map.of("smp", CATALOGUE), steward.catalogues());
        assertEquals(List.of("smp"), List.copyOf(steward.exports().keySet()));
    }

    @Test
    void aVersionIsDrawnAgainUntilARunningPainterDrewItsIcons() {
        final TestDatabase database = TestDatabase.fresh();
        final GameDataStore store = GameDataStore.using(database.dataSource());
        store.publish("smp", CATALOGUE);
        store.publish("limbo", CATALOGUE);
        assertEquals(List.of("26.2"), store.versionsToDraw("new"));

        store.storeIcons(
                "26.2", new GameDataStore.Icons(new byte[] {1, 2, 3}, 32, Map.of("minecraft:oak_log", 0), "old"));

        assertEquals(List.of(), store.versionsToDraw("old"));
        assertEquals(List.of("26.2"), store.versionsToDraw("new"), "a sheet another painter drew is drawn again");
        assertEquals(Map.of("26.2", "old"), store.iconPainters());

        store.storeIcons("26.2", new GameDataStore.Icons(new byte[] {9}, 16, Map.of(), "new"));

        assertEquals(List.of(), store.versionsToDraw("new"));
        assertEquals(Map.of("26.2", "new"), store.iconPainters());
        final GameDataStore.Icons icons = store.icons("26.2").orElseThrow();
        assertArrayEquals(new byte[] {9}, icons.png());
        assertEquals(
                new GameDataStore.IconIndex(16, Map.of(), "new"),
                store.iconIndex("26.2").orElseThrow());
    }

    @Test
    void aSheetDrawnBeforePaintersWereRecordedHasNoPainterAndIsDrawnAgain() {
        final TestDatabase database = TestDatabase.fresh();
        final GameDataStore store = GameDataStore.using(database.dataSource());
        store.publish("smp", CATALOGUE);
        Jdbi.create(database.dataSource()).useHandle(handle -> handle.execute("""
                INSERT INTO game_assets (minecraft_version, icons, columns, icon_index, fetched)
                VALUES ('26.2', decode('01', 'hex'), 32, '{}', now())"""));

        assertEquals(Map.of("26.2", ""), store.iconPainters());
        assertEquals(List.of("26.2"), store.versionsToDraw("any"));
    }

    @Test
    void stewardReadsTheGameDataAndWritesNoneOfIt() {
        final GameDataStore steward = GameDataStore.using(TestDatabase.fresh().dataSourceAs(DatabaseRole.STEWARD));

        assertThrows(UnableToExecuteStatementException.class, () -> steward.publish("smp", CATALOGUE));
        assertTrue(steward.catalogues().isEmpty());
    }
}
