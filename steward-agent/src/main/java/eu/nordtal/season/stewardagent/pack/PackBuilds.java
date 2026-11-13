package eu.nordtal.season.stewardagent.pack;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.database.document.DocumentStore;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.database.pack.PackBuildStore;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.packbuilder.PackBuilderMain;
import eu.nordtal.season.packbuilder.zip.ReproducibleZip;
import eu.nordtal.season.settings.PackState;
import eu.nordtal.season.stewardagent.apply.PackWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
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
    private final String baseUrl;
    private final Clock clock;

    /** The inputs of the active pack, once known. */
    private volatile @Nullable PackInputs built;
    /** The inputs a waiting build was asked for, so the same change asks only once. */
    private volatile @Nullable PackInputs wanted;
    /** The inputs of the build that runs now, so a change back during it still asks for a build. */
    private volatile @Nullable PackInputs building;
    /** Whether a build reads its inputs now, before {@link #building} names them, so a change then still asks. */
    private volatile boolean reading;

    private @Nullable Debounce debounce;
    /** The pack builder's code, so a release that changes only the builder builds the pack anew. */
    private List<Path> code = List.of();
    /** The fingerprint of {@link #facts}, {@link #roots} and {@link #code}, read once: the image does not change. */
    private @Nullable String image;

    /**
     * @param facts the vanilla facts zip the wheel reads
     * @param roots the pack's fixed folders, everything but the wheel
     * @param baseUrl the address Caddy serves the shelf under, without a trailing slash
     */
    PackBuilds(
            final Source source,
            final PackBuildStore record,
            final SettingStore settings,
            final PackShelf shelf,
            final PackZipper zipper,
            final Path facts,
            final List<Path> roots,
            final String baseUrl,
            final Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.record = Objects.requireNonNull(record, "record");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.shelf = Objects.requireNonNull(shelf, "shelf");
        this.zipper = Objects.requireNonNull(zipper, "zipper");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.roots = List.copyOf(roots);
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "");
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
    /** The file on the shelf that names the image files the active pack was built from; Caddy serves only zips. */
    static final String IMAGE_MARK = "image";

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
            final String baseUrl,
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
                new PackShelf(shelf, KEEP, clock),
                new ChildZipper(TIMEOUT),
                image.resolve("vanilla-facts.zip"),
                List.of(image.resolve("base")),
                baseUrl,
                clock);
        builds.documents = documents;
        builds.code = builderCode();
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
        return baseUrl + "/" + sha1 + ".zip";
    }

    /**
     * Settles the pack at the agent's start, and never throws, so the agent starts without its pack.
     *
     * It builds now unless the proxy's pack is on the shelf, and asks for one if the inputs or the image changed.
     */
    synchronized void start() {
        try {
            record.abandon(clock.instant(), "the agent stopped during the build");
        } catch (final RuntimeException unwritten) {
            log.warn("unfinished pack builds not ended: cause={}", unwritten.getMessage());
        }
        try {
            shelf.clearUnfinished();
            final String sha1 = PackState.read(settings).sha1();
            if (sha1 == null || !shelf.has(sha1)) {
                log.info("no pack of this agent is active, building one now: sha1={}", sha1);
                build();
                return;
            }
            if (PackWriter.write(settings, url(sha1), sha1)) {
                log.info("pack address set: url={}", url(sha1));
            }
            if (builtFromThisImage()) {
                record.active()
                        .filter(active -> sha1.equals(active.sha1()))
                        .ifPresent(active -> built = new PackInputs(active.prizes(), "", active.label()));
            } else {
                log.info("the image's pack files changed since the active pack was built, building it anew");
            }
        } catch (final RuntimeException failure) {
            log.warn("pack not settled at the start: cause={}", failure.getMessage());
            return;
        }
        requestIfChanged();
    }

    /** Asks for a build if the inputs changed before the hub ran, since a change then sent no signal to this agent. */
    public void catchUp() {
        requestIfChanged();
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
        final PackInputs running = building;
        final PackInputs last = waiting != null ? waiting : running != null ? running : built;
        if (!reading && last != null && last.drawsSame(now)) {
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
        reading = true;
        try {
            inputs = source.read();
        } catch (final IOException | RuntimeException unreadable) {
            reading = false;
            log.warn("pack inputs not read, the pack stays: cause={}", unreadable.getMessage());
            return active();
        }
        if (built != null && built.drawsSame(inputs)) {
            reading = false;
            return active();
        }
        building = inputs;
        reading = false;
        try {
            return build(inputs);
        } finally {
            building = null;
        }
    }

    private Optional<String> build(final PackInputs inputs) {
        final long id;
        try {
            id = record.start(clock.instant(), inputs.prizes(), inputs.label());
        } catch (final RuntimeException unwritten) {
            log.warn("pack build not started, its record was not written: cause={}", unwritten.getMessage());
            return active();
        }
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
            built = inputs;
            markImage();
            log.info("pack built: id={} sha1={} bytes={}", id, sha1, bytes.length);
            succeed(id, sha1, bytes.length);
            prune(sha1);
            return Optional.of(sha1);
        } catch (final IOException | RuntimeException failure) {
            log.warn("pack build failed, the active pack stays: id={} cause={}", id, failure.getMessage());
            try {
                record.fail(id, clock.instant(), String.valueOf(failure.getMessage()));
            } catch (final RuntimeException unwritten) {
                log.warn("failed pack build not recorded: id={} cause={}", id, unwritten.getMessage());
            }
            return active();
        } finally {
            if (work != null) {
                delete(work);
            }
        }
    }

    /** Records the good build; the proxy offers the pack already, whether or not the record is written. */
    private void succeed(final long id, final String sha1, final long size) {
        try {
            record.succeed(id, clock.instant(), sha1, size);
        } catch (final RuntimeException unwritten) {
            log.warn(
                    "pack built and offered, but its record was not written: id={} cause={}",
                    id,
                    unwritten.getMessage());
        }
    }

    /** Returns whether the active pack was built from this image's fixed files, as the mark on the shelf says. */
    private boolean builtFromThisImage() {
        try {
            final Path mark = shelf.dir().resolve(IMAGE_MARK);
            return Files.isRegularFile(mark) && Files.readString(mark).equals(image());
        } catch (final IOException failure) {
            log.warn("image of the active pack not read, building it anew: cause={}", failure.getMessage());
            return false;
        }
    }

    /** Notes on the shelf that the active pack was built from this image; without the note the next start builds. */
    private void markImage() {
        try {
            Files.writeString(shelf.dir().resolve(IMAGE_MARK), image());
        } catch (final IOException | RuntimeException failure) {
            log.warn("image of the new pack not noted, the next start builds it again: cause={}", failure.getMessage());
        }
    }

    private String image() throws IOException {
        if (image == null) {
            image = fingerprint(
                    facts, Stream.concat(roots.stream(), code.stream()).toList());
        }
        return image;
    }

    /** Returns the jar the pack builder runs from, none when it runs from a folder of classes, as in a test. */
    private static List<Path> builderCode() {
        try {
            final Path jar = Path.of(PackBuilderMain.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            return Files.isRegularFile(jar) ? List.of(jar) : List.of();
        } catch (final URISyntaxException | RuntimeException unknown) {
            log.warn(
                    "pack builder's code not found, a new builder alone builds no new pack: cause={}",
                    unknown.getMessage());
            return List.of();
        }
    }

    /**
     * Returns the SHA-1 over the facts zip and every file under {@code roots}, with its path; a root may be a file.
     *
     * @param facts the facts zip; a missing one counts as empty
     */
    static String fingerprint(final Path facts, final List<Path> roots) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (final NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
        if (Files.isRegularFile(facts)) {
            digest.update(Files.readAllBytes(facts));
        }
        for (final Path root : roots) {
            final List<Path> files;
            try (Stream<Path> walk = Files.walk(root)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (final Path file : files) {
                digest.update(("\0" + root.relativize(file) + "\0").getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Returns the SHA-1 the proxy offers now, empty if none or if the settings cannot be read. */
    private Optional<String> active() {
        try {
            return Optional.ofNullable(PackState.read(settings).sha1());
        } catch (final RuntimeException unreadable) {
            log.warn("active pack not read: cause={}", unreadable.getMessage());
            return Optional.empty();
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
