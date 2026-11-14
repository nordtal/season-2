package eu.nordtal.season.stewardagent.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.time.ManualScheduler;
import eu.nordtal.season.database.notify.Notifications;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.database.pack.PackBuildStore;
import eu.nordtal.season.packbuilder.zip.ReproducibleZip;
import eu.nordtal.season.settings.MemorySettingStore;
import eu.nordtal.season.settings.PackState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** A good build makes the proxy offer the new pack; a failed one leaves the active pack as it was. */
class PackBuildsTest {

    @TempDir
    Path shelfDir;

    private final MemorySettingStore settings = new MemorySettingStore();
    private final Recorded record = new Recorded();
    private final AtomicReference<PackInputs> inputs = new AtomicReference<>(
            new PackInputs("[{\"item\":\"diamond\",\"amount\":2}]", "{}", Map.of("de", "Drehen")));
    private final AtomicInteger zips = new AtomicInteger();
    private final ManualScheduler scheduler = new ManualScheduler();
    private boolean failing;
    private Runnable duringZip = () -> {};
    private Runnable afterRead = () -> {};

    private PackBuilds builds() {
        final PackBuilds builds = new PackBuilds(
                () -> {
                    final PackInputs read = inputs.get();
                    final Runnable hook = afterRead;
                    afterRead = () -> {};
                    hook.run();
                    return read;
                },
                record,
                settings,
                new PackShelf(shelfDir, 5, Clock.systemUTC()),
                (facts, prizes, texts, zip, roots) -> {
                    zips.incrementAndGet();
                    duringZip.run();
                    if (failing) {
                        throw new IOException("the pack builder exited with 1");
                    }
                    Files.writeString(zip, Files.readString(prizes), StandardCharsets.UTF_8);
                },
                shelfDir.resolve("facts.zip"),
                List.of(),
                "https://pack.example/",
                Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC));
        builds.debounceWith(new Debounce(scheduler, Duration.ofSeconds(30), scheduler, builds::build));
        return builds;
    }

    private static PackInputs inputsOf(final String prizes) {
        return new PackInputs(prizes, "{}", Map.of("de", "Drehen"));
    }

    /** The active build as the database returns it: jsonb spaces the prizes and orders their keys by length. */
    private static PackBuildStore.PackBuild activeRow(final String sha1, final String prizes) {
        return new PackBuildStore.PackBuild(
                1,
                PackBuildStore.State.ACTIVE,
                Instant.EPOCH,
                Instant.EPOCH,
                prizes,
                Map.of("de", "Drehen"),
                sha1,
                4L,
                Duration.ZERO,
                null);
    }

    private String shelved(final String sha1) throws IOException {
        Files.writeString(shelfDir.resolve(sha1 + ".zip"), "pack");
        settings.set("proxy", "pack", "sha1", sha1);
        Files.writeString(
                shelfDir.resolve(PackBuilds.IMAGE_MARK),
                PackBuilds.fingerprint(shelfDir.resolve("facts.zip"), List.of()));
        return sha1;
    }

    @Test
    void aNewImageBuildsThePackAnewAfterTheStart() throws IOException {
        final String sha1 = shelved("f".repeat(40));
        record.active = Optional.of(activeRow(sha1, "[{\"item\": \"diamond\", \"amount\": 2}]"));
        Files.writeString(shelfDir.resolve(PackBuilds.IMAGE_MARK), "the image before the update");

        builds().start();
        assertEquals(0, zips.get());
        scheduler.runPending();

        assertEquals(1, zips.get());
        assertEquals(sha1Of(inputs.get().prizes()), PackState.read(settings).sha1());
    }

    @Test
    void theFingerprintFollowsTheImageFiles() throws IOException {
        final Path base = Files.createDirectories(shelfDir.resolve("base"));
        Files.writeString(base.resolve("pack.mcmeta"), "{}");
        final String before = PackBuilds.fingerprint(shelfDir.resolve("facts.zip"), List.of(base));

        Files.writeString(base.resolve("pack.mcmeta"), "{\"pack\":{}}");

        assertFalse(before.equals(PackBuilds.fingerprint(shelfDir.resolve("facts.zip"), List.of(base))));
    }

    @Test
    void aRestartWithTheSamePrizesAsTheDatabaseSpellsThemBuildsNothing() throws IOException {
        final String sha1 = shelved("c".repeat(40));
        record.active = Optional.of(activeRow(sha1, "[{\"item\": \"diamond\", \"amount\": 2}]"));

        final PackBuilds builds = builds();
        builds.start();
        builds.catchUp();
        scheduler.runPending();

        assertEquals(0, zips.get());
        assertEquals(sha1, PackState.read(settings).sha1());
    }

    @Test
    void aChangeBackDuringABuildEndsWithThePackOfTheChangeBack() {
        final PackBuilds builds = builds();
        final String first = builds.build().orElseThrow();
        final PackInputs original = inputs.get();

        inputs.set(inputsOf("[{\"item\":\"emerald\",\"amount\":1}]"));
        builds.requestIfChanged();
        duringZip = () -> {
            duringZip = () -> {};
            inputs.set(original);
            builds.requestIfChanged();
        };
        scheduler.runPending();
        scheduler.runPending();

        assertEquals(first, PackState.read(settings).sha1());
        assertEquals(3, zips.get());
    }

    @Test
    void aChangeBackWhileABuildReadsItsInputsEndsWithThePackOfTheChangeBack() {
        final PackBuilds builds = builds();
        final String first = builds.build().orElseThrow();
        final PackInputs original = inputs.get();

        inputs.set(inputsOf("[{\"item\":\"emerald\",\"amount\":1}]"));
        builds.requestIfChanged();
        afterRead = () -> {
            inputs.set(original);
            builds.requestIfChanged();
        };
        scheduler.runPending();
        scheduler.runPending();

        assertEquals(first, PackState.read(settings).sha1());
        assertEquals(3, zips.get());
    }

    @Test
    void aTrailingSlashOfTheBaseAddressIsDropped() {
        assertEquals("https://pack.example/" + "a".repeat(40) + ".zip", builds().url("a".repeat(40)));
    }

    @Test
    void aSingleFileCountsInTheFingerprint() throws IOException {
        final Path jar = Files.writeString(shelfDir.resolve("app.jar"), "builder 1");
        final String before = PackBuilds.fingerprint(shelfDir.resolve("facts.zip"), List.of(jar));

        Files.writeString(jar, "builder 2");

        assertFalse(before.equals(PackBuilds.fingerprint(shelfDir.resolve("facts.zip"), List.of(jar))));
    }

    @Test
    void aChangeWhileTheAgentWasDownIsBuiltAfterTheStart() throws IOException {
        final String sha1 = shelved("d".repeat(40));
        record.active = Optional.of(activeRow(sha1, "[{\"item\": \"gold_ingot\", \"amount\": 5}]"));

        builds().start();
        assertEquals(0, zips.get());
        scheduler.runPending();

        assertEquals(1, zips.get());
        assertEquals(sha1Of(inputs.get().prizes()), PackState.read(settings).sha1());
    }

    @Test
    void aChangeBeforeTheHubRanIsBuiltAtTheCatchUp() throws IOException {
        final String sha1 = shelved("e".repeat(40));
        record.active = Optional.of(activeRow(sha1, "[{\"item\": \"diamond\", \"amount\": 2}]"));
        final PackBuilds builds = builds();
        builds.start();

        inputs.set(inputsOf("[{\"item\":\"emerald\",\"amount\":1}]"));
        builds.catchUp();
        scheduler.runPending();

        assertEquals(1, zips.get());
        assertEquals(sha1Of(inputs.get().prizes()), PackState.read(settings).sha1());
    }

    @Test
    void aRecordThatCannotBeWrittenNeitherStopsTheStartNorUndoesTheNewPack() {
        record.broken = true;

        builds().start();

        assertEquals(1, zips.get());
        final String sha1 = sha1Of(inputs.get().prizes());
        assertEquals(sha1, PackState.read(settings).sha1());
        assertFalse(record.calls.stream().anyMatch(call -> call.startsWith("fail")));
    }

    @Test
    void aFailedBuildWhoseRecordCannotBeWrittenStillReturnsTheActivePack() {
        final PackBuilds builds = builds();
        final String first = builds.build().orElseThrow();

        inputs.set(inputsOf("[{\"item\":\"emerald\",\"amount\":1}]"));
        failing = true;
        record.broken = true;

        assertEquals(Optional.of(first), builds.build());
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
        assertTrue(Files.exists(shelfDir.resolve(PackBuilds.IMAGE_MARK)));
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

    @Test
    void anAdminsAskBuildsOnceHoweverOftenTheHubWakes() throws Exception {
        final PackBuilds builds = builds();
        // An ask from before the start, which the start settled.
        record.asked = Optional.of(Instant.EPOCH);
        final BlockingQueue<Boolean> wakes = new LinkedBlockingQueue<>();
        final Semaphore waiting = new Semaphore(0);
        final SignalHub hub = new SignalHub(
                channels -> new Notifications() {
                    @Override
                    public boolean awaitNotification(final Duration timeout) throws SQLException {
                        waiting.release();
                        try {
                            return wakes.take();
                        } catch (final InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new SQLException("interrupted", interrupted);
                        }
                    }

                    @Override
                    public void close() {
                        wakes.add(Boolean.TRUE);
                    }
                },
                "pack-builds-test",
                LoggerFactory.getLogger(PackBuildsTest.class));
        builds.listen(hub, null);
        hub.start();
        try {
            waitForRound(waiting);
            for (int wake = 0; wake < 3; wake++) {
                wakes.add(Boolean.TRUE);
                waitForRound(waiting);
            }
            assertEquals(0, zips.get(), "the hub's wakes alone build nothing");

            record.asked = Optional.of(Instant.EPOCH.plusSeconds(1));
            wakes.add(Boolean.TRUE);
            waitForRound(waiting);
            wakes.add(Boolean.TRUE);
            waitForRound(waiting);

            assertEquals(1, zips.get(), "one ask builds once");
        } finally {
            hub.close();
        }
    }

    /** Waits until the hub re-read everything and waits for the next signal. */
    private static void waitForRound(final Semaphore waiting) throws InterruptedException {
        assertTrue(waiting.tryAcquire(10, TimeUnit.SECONDS), "the hub stopped re-reading");
    }

    /** The calls the builds made on their record, in order. */
    private static final class Recorded implements PackBuildStore {

        final List<String> calls = new ArrayList<>();
        Optional<PackBuild> active = Optional.empty();
        volatile Optional<Instant> asked = Optional.empty();
        /** Whether every write but {@code start} fails, as on a database hiccup after the build began. */
        boolean broken;

        private void write(final String call) {
            calls.add(call);
            if (broken) {
                throw new IllegalStateException("the database is gone: " + call);
            }
        }

        @Override
        public long start(final Instant started, final String prizes, final Map<String, String> label) {
            calls.add("start");
            return calls.size();
        }

        @Override
        public void succeed(final long id, final Instant finished, final String sha1, final long sizeBytes) {
            write("succeed " + sha1);
        }

        @Override
        public void fail(final long id, final Instant finished, final String error) {
            write("fail " + error);
        }

        @Override
        public void abandon(final Instant finished, final String error) {
            write("abandon");
        }

        @Override
        public void askAgain() {
            calls.add("ask again");
        }

        @Override
        public Optional<Instant> asked() {
            return asked;
        }

        @Override
        public Optional<PackBuild> active() {
            return active;
        }

        @Override
        public List<PackBuild> recent(final int limit) {
            return List.of();
        }
    }
}
