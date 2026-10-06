package eu.nordtal.season.stewardagent.gamedata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.game.GameCatalogue;
import eu.nordtal.season.database.game.GameDataStore;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Icons are drawn once per version and painter, only with consent, and a failed version rests before a retry. */
class GameAssetsTest {

    private final Store store = new Store();
    private final List<String> opened = new ArrayList<>();
    private final List<String> asked = new ArrayList<>();
    private final AtomicBoolean consented = new AtomicBoolean(true);
    private boolean mojangAnswers = true;
    private Instant now = Instant.parse("2026-10-02T20:00:00Z");

    private final ClientJars jars = version -> {
        opened.add(version);
        if (!mojangAnswers) {
            throw new IOException("piston-data.mojang.com did not answer");
        }
        return new ClientJars.Jar() {
            @Override
            public byte[] bytes(final String path) {
                if (path.startsWith("assets/minecraft/items/")) {
                    asked.add(path.substring("assets/minecraft/items/".length()));
                }
                return path.equals("assets/minecraft/items/stone.json")
                        ? "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        : null;
            }

            @Override
            public void close() {}
        };
    };

    private GameAssets assets() {
        return new GameAssets(store, jars, consented::get, new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(final java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        });
    }

    @Test
    void withoutConsentMojangIsNeverAsked() {
        consented.set(false);

        assets().drawMissing();

        assertEquals(List.of(), opened);
        assertTrue(store.icons.isEmpty());
    }

    @Test
    void aVersionWithoutIconsGetsTheItemsItsServersName() {
        assets().drawMissing();

        assertEquals(List.of("26.2"), opened);
        assertEquals(List.of("dirt.json", "stone.json"), asked);
        assertTrue(store.icons.containsKey("26.2"));
    }

    @Test
    void aSheetThePresentPainterDrewIsLeftAlone() {
        final GameAssets assets = assets();
        assets.drawMissing();
        opened.clear();

        assets.drawMissing();

        assertEquals(List.of(), opened);
        assertEquals(Painter.id(), store.icons.get("26.2").index().painter());
    }

    @Test
    void aSheetAnotherPainterDrewIsDrawnAgainByThisOne() {
        store.icons.put("26.2", new GameDataStore.Icons(new byte[] {1}, 32, Map.of(), "an older painter"));

        assets().drawMissing();

        assertEquals(List.of("26.2"), opened);
        assertEquals(Painter.id(), store.icons.get("26.2").index().painter());
    }

    @Test
    void aVersionThatFailedRestsAnHourBeforeMojangIsAskedAgain() {
        mojangAnswers = false;
        final GameAssets assets = assets();

        assets.drawMissing();
        now = now.plusSeconds(59 * 60);
        assets.drawMissing();
        assertEquals(List.of("26.2"), opened);

        now = now.plusSeconds(60);
        assets.drawMissing();
        assertEquals(List.of("26.2", "26.2"), opened);
    }

    /** Two servers on 26.2, no icons yet. */
    private static final class Store implements GameDataStore {

        final Map<String, Icons> icons = new HashMap<>();

        @Override
        public void publish(final String server, final GameCatalogue catalogue) {}

        @Override
        public Map<String, Instant> exports() {
            return Map.of();
        }

        @Override
        public Map<String, GameCatalogue> catalogues() {
            return Map.of("smp", catalogue("minecraft:stone"), "limbo", catalogue("minecraft:dirt"));
        }

        private static GameCatalogue catalogue(final String item) {
            return new GameCatalogue(
                    "26.2",
                    List.of(),
                    Map.of("item", List.of(GameCatalogue.Entry.named(item, "item", item))),
                    Map.of());
        }

        @Override
        public List<String> versionsToDraw(final String painter) {
            final Icons drawn = icons.get("26.2");
            return drawn != null && drawn.index().painter().equals(painter) ? List.of() : List.of("26.2");
        }

        @Override
        public Map<String, String> iconPainters() {
            return Map.of();
        }

        @Override
        public void storeIcons(final String minecraftVersion, final Icons drawn) {
            icons.put(minecraftVersion, drawn);
        }

        @Override
        public Optional<Icons> icons(final String minecraftVersion) {
            return Optional.ofNullable(icons.get(minecraftVersion));
        }

        @Override
        public Optional<IconIndex> iconIndex(final String minecraftVersion) {
            return icons(minecraftVersion).map(Icons::index);
        }
    }
}
