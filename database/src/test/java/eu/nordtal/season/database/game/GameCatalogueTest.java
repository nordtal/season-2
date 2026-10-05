package eu.nordtal.season.database.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Several servers' catalogues read as one: each entry once, the datapacks of all, other versions left out. */
class GameCatalogueTest {

    private static GameCatalogue.Entry entry(final String id) {
        return GameCatalogue.Entry.named(id, null, null);
    }

    @Test
    void theUnionHoldsEachEntryOnceAndWhatOnlyOneServerHas() {
        final GameCatalogue smp = new GameCatalogue(
                "26.2",
                List.of("vanilla", "nordtal"),
                Map.of("advancement", List.of(entry("minecraft:story/root"), entry("nordtal:smp/first_night"))),
                Map.of("item", List.of(new GameCatalogue.Tag("minecraft:logs", List.of("minecraft:oak_log")))));
        final GameCatalogue limbo = new GameCatalogue(
                "26.2",
                List.of("vanilla"),
                Map.of("advancement", List.of(entry("minecraft:story/root"))),
                Map.of("item", List.of(new GameCatalogue.Tag("minecraft:logs", List.of("minecraft:oak_log")))));
        final GameCatalogue older = new GameCatalogue(
                "26.1", List.of("old"), Map.of("advancement", List.of(entry("minecraft:gone"))), Map.of());

        final GameCatalogue union = GameCatalogue.union("26.2", List.of(smp, limbo, older));

        assertEquals(List.of("nordtal", "vanilla"), union.datapacks());
        assertEquals(
                List.of(entry("minecraft:story/root"), entry("nordtal:smp/first_night")),
                union.registries().get("advancement"));
        assertEquals(1, union.tags().get("item").size());
    }
}
