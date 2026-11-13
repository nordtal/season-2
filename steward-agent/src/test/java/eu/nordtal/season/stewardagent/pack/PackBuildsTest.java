package eu.nordtal.season.stewardagent.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.pack.PackBuildStore;
import eu.nordtal.season.packbuilder.zip.ReproducibleZip;
import eu.nordtal.season.settings.MemorySettingStore;
import eu.nordtal.season.settings.PackState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A good build makes the proxy offer the new pack; a failed one leaves the active pack as it was. */
class PackBuildsTest {

    @TempDir
    Path shelfDir;

    private final MemorySettingStore settings = new MemorySettingStore();
    private final Recorded record = new Recorded();
    private final AtomicReference<PackInputs> inputs = new AtomicReference<>(
            new PackInputs("[{\"item\":\"diamond\",\"amount\":2}]", "{}", Map.of("de", "Drehen")));
    private final AtomicInteger zips = new AtomicInteger();
    private boolean failing;

    private PackBuilds builds() {
        return new PackBuilds(
                inputs::get,
                record,
                settings,
                new PackShelf(shelfDir, 5),
                (facts, prizes, texts, zip, roots) -> {
                    zips.incrementAndGet();
                    if (failing) {
                        throw new IOException("the pack builder exited with 1");
                    }
                    Files.writeString(zip, Files.readString(prizes), StandardCharsets.UTF_8);
                },
                shelfDir.resolve("facts.zip"),
                List.of(),
                "pack.example",
                Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC));
    }

    private static String sha1Of(final String prizes) {
        return ReproducibleZip.sha1(prizes.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aGoodBuildPutsTheZipOnTheShelfAndPointsTheProxyAtIt() {
        final Optional<String> sha1 = builds().build();

        final String expected = sha1Of(inputs.get().prizes());
        assertEquals(Optional.of(expected), sha1);
        assertTrue(Files.exists(shelfDir.resolve(expected + ".zip")));
        assertEquals(new PackState("https://pack.example/" + expected + ".zip", expected), PackState.read(settings));
        assertEquals(List.of("start", "succeed " + expected), record.calls);
    }

    @Test
    void aFailedBuildLeavesTheActivePackAsItWas() {
        final PackBuilds builds = builds();
        final String first = builds.build().orElseThrow();
        final PackState before = PackState.read(settings);

        inputs.set(new PackInputs("[{\"item\":\"emerald\",\"amount\":1}]", "{}", Map.of("de", "Drehen")));
        failing = true;
        final Optional<String> after = builds.build();

        assertEquals(Optional.of(first), after);
        assertEquals(before, PackState.read(settings));
        assertEquals("fail the pack builder exited with 1", record.calls.getLast());
        assertTrue(Files.exists(shelfDir.resolve(first + ".zip")));
    }

    @Test
    void theSameInputsAreNotBuiltTwice() {
        final PackBuilds builds = builds();
        builds.build();
        builds.build();

        assertEquals(1, zips.get());
    }

    @Test
    void aStartWithoutAPackOnTheShelfBuildsOneNow() {
        settings.set("proxy", "pack", "sha1", "a".repeat(40));

        builds().start();

        assertEquals(1, zips.get());
        assertEquals(sha1Of(inputs.get().prizes()), PackState.read(settings).sha1());
        assertEquals("abandon", record.calls.getFirst());
    }

    @Test
    void aStartWithItsPackOnTheShelfBuildsNothing() throws IOException {
        final String sha1 = "b".repeat(40);
        Files.writeString(shelfDir.resolve(sha1 + ".zip"), "pack");
        settings.set("proxy", "pack", "sha1", sha1);

        builds().start();

        assertEquals(0, zips.get());
        assertEquals(
                "https://pack.example/" + sha1 + ".zip",
                PackState.read(settings).url());
        assertFalse(record.calls.contains("start"));
    }

    /** The calls the builds made on their record, in order. */
    private static final class Recorded implements PackBuildStore {

        final List<String> calls = new ArrayList<>();

        @Override
        public long start(final Instant started, final String prizes, final Map<String, String> label) {
            calls.add("start");
            return calls.size();
        }

        @Override
        public void succeed(final long id, final Instant finished, final String sha1, final long sizeBytes) {
            calls.add("succeed " + sha1);
        }

        @Override
        public void fail(final long id, final Instant finished, final String error) {
            calls.add("fail " + error);
        }

        @Override
        public void abandon(final Instant finished, final String error) {
            calls.add("abandon");
        }

        @Override
        public Optional<PackBuild> active() {
            return Optional.empty();
        }

        @Override
        public List<PackBuild> recent(final int limit) {
            return List.of();
        }
    }
}
