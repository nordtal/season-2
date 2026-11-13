package eu.nordtal.season.stewardagent.pack;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.database.document.DocumentStore;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.database.pack.PackBuildStore;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.packbuilder.zip.ReproducibleZip;
import eu.nordtal.season.settings.PackState;
import eu.nordtal.season.stewardagent.apply.PackWriter;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the resource pack whenever smp's wheel prizes or the wheel's label change, and makes the proxy offer it.
 *
 * A build writes {@code <sha1>.zip} onto Caddy's shelf, then the proxy's url and sha1; a failed one changes neither.
 */
public final class PackBuilds {

    private static final Logger log = LoggerFactory.getLogger(PackBuilds.class);

    /** Reads the inputs as they apply now. */
    @FunctionalInterface
    interface Source {

        PackInputs read() throws IOException;
    }

    private final Source source;
    private final PackBuildStore record;
    private final SettingStore settings;
    private final PackShelf shelf;
    private final PackZipper zipper;
    private final Path facts;
    private final List<Path> roots;
    private final String host;
    private final Clock clock;

    /** The inputs of the active pack, once known. */
    private volatile @Nullable PackInputs built;
    /** The inputs a waiting build was asked for, so the same change asks only once. */
    private volatile @Nullable PackInputs wanted;

    private @Nullable Debounce debounce;

    /**
     * @param facts the vanilla facts zip the wheel reads
     * @param roots the pack's fixed folders, everything but the wheel
     * @param host the name Caddy serves the shelf under
     */
    PackBuilds(
            final Source source,
            final PackBuildStore record,
            final SettingStore settings,
            final PackShelf shelf,
            final PackZipper zipper,
            final Path facts,
            final List<Path> roots,
            final String host,
            final Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.record = Objects.requireNonNull(record, "record");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.shelf = Objects.requireNonNull(shelf, "shelf");
        this.zipper = Objects.requireNonNull(zipper, "zipper");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.roots = List.copyOf(roots);
        this.host = Objects.requireNonNull(host, "host");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The quiet time after the last change before the build, so an admin's edits in a row make one pack. */
    static final Duration QUIET = Duration.ofSeconds(30);
    /** The time a build may take before it counts as failed. */
    static final Duration TIMEOUT = Duration.ofMinutes(5);
    /** How many of the newest packs stay on the shelf besides the active one. */
    static final int KEEP = 5;
    /** The service whose texts the pack draws. */
    private static final String NAMESPACE = "smp";

    private @Nullable AppliedDocuments documents;

    /**
     * Returns the pack builds from the files this agent's image ships.
     *
     * @param image the image's pack folder: {@code base/}, {@code vanilla-facts.zip}, {@code wheel-prizes.json}
     *     and {@code documents/}
     * @param shelf the folder Caddy serves
     */
    public static PackBuilds fromImage(
            final DataSource dataSource,
            final SettingStore settings,
            final Path image,
            final Path shelf,
            final String host,
            final Scheduler scheduler,
            final Clock clock)
            throws IOException {
        final URLClassLoader loader =
                new URLClassLoader(new URL[] {image.resolve("documents").toUri().toURL()}, null);
        final AppliedDocuments documents = new AppliedDocuments(loader, Set.of(NAMESPACE));
        final Path prizes = image.resolve("wheel-prizes.json");
        Files.createDirectories(shelf);
        final PackBuilds builds = new PackBuilds(
                () -> PackInputs.read(settings, documents, prizes),
                PackBuildStore.using(dataSource),
                settings,
                new PackShelf(shelf, KEEP),
                new ChildZipper(TIMEOUT),
                image.resolve("vanilla-facts.zip"),
                List.of(image.resolve("base")),
                host,
                clock);
        builds.documents = documents;
        builds.debounceWith(new Debounce(scheduler, QUIET, scheduler.serial(), builds::build));
        return builds;
    }

    /**
     * Settles the pack at the agent's start, with the admins' texts read first, as {@link #start()} does.
     *
     * @param store where the admins' documents are, read now because the hub is not running yet
     */
    public void start(final DocumentStore store) {
        if (documents != null) {
            try {
                documents.hold(store.applied(Set.of(NAMESPACE)));
            } catch (final RuntimeException unreadable) {
                log.warn("admins' wheel texts not read, the packaged ones count: cause={}", unreadable.getMessage());
            }
        }
        start();
    }

    /** Asks for a build on every change of smp's settings or of the admins' documents. Call before the hub starts. */
    public void listen(final SignalHub signals, final DocumentStore store) {
        signals.on(Channel.SETTINGS, "the wheel prizes", this::requestIfChanged);
        if (documents != null) {
            documents.follow(store, signals, this::requestIfChanged);
        }
    }

    /** Sets where a change asks for its build. */
    void debounceWith(final Debounce debounce) {
        this.debounce = debounce;
    }

    /** Returns the address the proxy sends for the pack {@code sha1}. */
    String url(final String sha1) {
        return "https://" + host + "/" + sha1 + ".zip";
    }

    /**
     * Settles the pack at the agent's start.
     *
     * It records an unfinished build as failed, then builds unless the proxy's pack is on the shelf already.
     */
    synchronized void start() {
        record.abandon(clock.instant(), "the agent stopped during the build");
        shelf.clearUnfinished();
        final PackState state = PackState.read(settings);
        final String sha1 = state.sha1();
        if (sha1 == null || !shelf.has(sha1)) {
            log.info("no pack of this agent is active, building one now: sha1={}", sha1);
            build();
            return;
        }
        if (PackWriter.write(settings, url(sha1), sha1)) {
            log.info("pack address set: url={}", url(sha1));
        }
        record.active()
                .filter(active -> sha1.equals(active.sha1()))
                .ifPresent(active -> built = new PackInputs(active.prizes(), "", active.label()));
    }

    /** Asks for a build after the quiet time if the inputs draw another pack than the active one. */
    void requestIfChanged() {
        final PackInputs now;
        try {
            now = source.read();
        } catch (final IOException | RuntimeException unreadable) {
            log.warn("pack inputs not read, the pack stays: cause={}", unreadable.getMessage());
            return;
        }
        final PackInputs waiting = wanted;
        final PackInputs active = built;
        if (waiting != null ? waiting.drawsSame(now) : active != null && active.drawsSame(now)) {
            return;
        }
        wanted = now;
        final Debounce lane = debounce;
        if (lane != null) {
            lane.request();
        }
    }

    /**
     * Builds the pack from the inputs as they are now, unless the active pack was built from the same.
     *
     * @return the SHA-1 of the active pack afterwards, empty if there is none
     */
    synchronized Optional<String> build() {
        wanted = null;
        final PackInputs inputs;
        try {
            inputs = source.read();
        } catch (final IOException | RuntimeException unreadable) {
            log.warn("pack inputs not read, the pack stays: cause={}", unreadable.getMessage());
            return Optional.ofNullable(PackState.read(settings).sha1());
        }
        if (built != null && built.drawsSame(inputs)) {
            return Optional.ofNullable(PackState.read(settings).sha1());
        }
        final long id = record.start(clock.instant(), inputs.prizes(), inputs.label());
        Path work = null;
        try {
            work = shelf.workspace();
            final Path prizes = Files.writeString(work.resolve("prizes.json"), inputs.prizes(), StandardCharsets.UTF_8);
            final Path texts = Files.writeString(work.resolve("texts.json"), inputs.texts(), StandardCharsets.UTF_8);
            final Path zip = work.resolve("pack.zip");
            zipper.zip(facts, prizes, texts, zip, roots);
            final byte[] bytes = Files.readAllBytes(zip);
            final String sha1 = ReproducibleZip.sha1(bytes);
            shelf.put(zip, sha1);
            PackWriter.write(settings, url(sha1), sha1);
            record.succeed(id, clock.instant(), sha1, bytes.length);
            built = inputs;
            log.info("pack built: id={} sha1={} bytes={}", id, sha1, bytes.length);
            prune(sha1);
            return Optional.of(sha1);
        } catch (final IOException | RuntimeException failure) {
            log.warn("pack build failed, the active pack stays: id={} cause={}", id, failure.getMessage());
            record.fail(id, clock.instant(), String.valueOf(failure.getMessage()));
            return Optional.ofNullable(PackState.read(settings).sha1());
        } finally {
            if (work != null) {
                delete(work);
            }
        }
    }

    private void prune(final String active) {
        try {
            final List<String> gone = shelf.prune(active);
            if (!gone.isEmpty()) {
                log.info("old packs removed: files={}", gone);
            }
        } catch (final IOException | RuntimeException failure) {
            log.warn("old packs not removed: cause={}", failure.getMessage());
        }
    }

    private static void delete(final Path work) {
        try {
            PackShelf.delete(work);
        } catch (final IOException failure) {
            log.warn("build folder not removed: path={} cause={}", work, failure.getMessage());
        }
    }
}
