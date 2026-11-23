package eu.nordtal.season.stewardagent.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.time.ManualScheduler;
import eu.nordtal.season.packbuilder.map.MapPack;
import eu.nordtal.season.settings.MemorySettingStore;
import eu.nordtal.season.settings.PackState;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A night with new tiles sets a new map pack; a night with the same tiles sets nothing. */
class MapPacksTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String LANGUAGE = "6f1ed002ab5595859014ebf0951522d9d0f2ee34";

    @TempDir
    Path tiles;

    @TempDir
    Path shelfDir;

    private final MemorySettingStore settings = new MemorySettingStore();
    private final ManualScheduler scheduler = new ManualScheduler();

    private MapPacks maps() {
        return new MapPacks(
                tiles,
                settings,
                new PackShelf(shelfDir, 5, Clock.systemUTC()),
                (from, zip) -> Files.write(zip, MapPack.build(from)),
                "https://pack.example/",
                scheduler,
                BERLIN,
                Clock.fixed(Instant.parse("2026-11-23T03:00:00Z"), BERLIN));
    }

    /** Writes BlueMap's level-1 tile at x 0 and z 0 in one {@code colour}, flat at height 64. */
    private void tile(final int colour) throws IOException {
        final BufferedImage image = new BufferedImage(501, 1002, BufferedImage.TYPE_INT_ARGB);
        for (int z = 0; z < 500; z++) {
            for (int x = 0; x < 500; x++) {
                image.setRGB(x, z, colour);
                image.setRGB(x, 501 + z, 0xFF0F0040);
            }
        }
        final Path file = tiles.resolve("1/x0/z0.png");
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
    }

    /** Returns the map pack the proxy offers. */
    private PackState offered() {
        return PackState.readMap(settings);
    }

    @Test
    void twoNightsWithTheSameTilesSetTheMapPackOnce() throws IOException {
        tile(0xFF336699);
        final MapPacks maps = maps();

        assertTrue(maps.build());
        final PackState first = offered();
        assertFalse(maps.build());

        assertEquals(first, offered());
        assertEquals("https://pack.example/" + first.sha1() + ".zip", first.url());
        assertTrue(Files.isRegularFile(shelfDir.resolve(first.sha1() + ".zip")));
        assertEquals(List.of(first.sha1() + ".zip"), shelved());
    }

    @Test
    void aChangedTileSetsANewMapPackAndKeepsTheLanguagePack() throws IOException {
        settings.set("proxy", "pack", "url", "https://pack.example/" + LANGUAGE + ".zip")
                .set("proxy", "pack", "sha1", LANGUAGE);
        tile(0xFF336699);
        final MapPacks maps = maps();
        maps.build();
        final String first = offered().sha1();

        tile(0xFF336698);

        assertTrue(maps.build());
        assertNotEquals(first, offered().sha1());
        assertEquals(LANGUAGE, PackState.read(settings).sha1());
    }

    @Test
    void aMapPackLostFromTheShelfIsPutBack() throws IOException {
        tile(0xFF336699);
        final MapPacks maps = maps();
        maps.build();
        final String sha1 = offered().sha1();
        Files.delete(shelfDir.resolve(sha1 + ".zip"));

        assertTrue(maps.build());

        assertTrue(Files.isRegularFile(shelfDir.resolve(sha1 + ".zip")));
    }

    @Test
    void withoutTilesNothingIsBuilt() throws IOException {
        assertFalse(maps().build());

        assertNull(offered().sha1());
        assertEquals(List.of(), shelved());
    }

    @Test
    void aFailedBuildKeepsTheMapPackAndLeavesNoBuildFolder() throws IOException {
        tile(0xFF336699);
        maps().build();
        final PackState before = offered();
        final MapPacks failing = new MapPacks(
                tiles,
                settings,
                new PackShelf(shelfDir, 5, Clock.systemUTC()),
                (from, zip) -> {
                    throw new IOException("the pack builder exited with 1");
                },
                "https://pack.example",
                scheduler,
                BERLIN,
                Clock.systemUTC());

        assertFalse(failing.build());

        assertEquals(before, offered());
        assertEquals(List.of(before.sha1() + ".zip"), shelved());
    }

    @Test
    void aStartWithoutAMapPackBuildsSoonAndThenEachNight() throws IOException {
        tile(0xFF336699);
        maps().start();

        assertEquals(List.of(MapPacks.SOON), delays());
        scheduler.runPending();

        assertTrue(offered().present());
        // The fixed clock reads 04:00 in Berlin, so the next build is 75 minutes away.
        assertEquals(List.of(Duration.ofMinutes(75)), delays());
    }

    @Test
    void aStartWithItsMapPackOnTheShelfWaitsForTheNight() throws IOException {
        tile(0xFF336699);
        maps().build();

        maps().start();

        assertEquals(List.of(Duration.ofMinutes(75)), delays());
    }

    @Test
    void theNextBuildIsTomorrowOnceTodaysTimeHasPassed() {
        final LocalTime at = LocalTime.of(5, 15);

        assertEquals(Duration.ofMinutes(15), MapPacks.untilNext(Instant.parse("2026-11-23T04:00:00Z"), BERLIN, at));
        assertEquals(Duration.ofDays(1), MapPacks.untilNext(Instant.parse("2026-11-23T04:15:00Z"), BERLIN, at));
        // The night clocks go back is 25 hours long.
        assertEquals(Duration.ofHours(25), MapPacks.untilNext(Instant.parse("2026-10-24T03:15:00Z"), BERLIN, at));
    }

    private List<Duration> delays() {
        return scheduler.pending().stream().map(ManualScheduler.Pending::delay).toList();
    }

    private List<String> shelved() throws IOException {
        try (Stream<Path> files = Files.list(shelfDir)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }
}
