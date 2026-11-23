package eu.nordtal.season.stewardagent.pack;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.packbuilder.zip.ReproducibleZip;
import eu.nordtal.season.settings.PackState;
import eu.nordtal.season.stewardagent.apply.PackWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the map pack each night from BlueMap's tiles and sets it as the proxy's map pack.
 *
 * A night whose tiles draw the same images sets nothing, so the proxy keeps its offer and no player downloads again.
 */
public final class MapPacks {

    private static final Logger log = LoggerFactory.getLogger(MapPacks.class);

    /** The time of day of the build, after the nightly backup at 04:45 has stopped writing. */
    static final LocalTime AT = LocalTime.of(5, 15);
    /** The time a build may take before it counts as failed; it reads 400 tiles. */
    static final Duration TIMEOUT = Duration.ofMinutes(10);
    /** The wait at the agent's start before a missing map pack is built, so the start itself stays quick. */
    static final Duration SOON = Duration.ofMinutes(1);

    private final Path tiles;
    private final SettingStore settings;
    private final PackShelf shelf;
    private final MapZipper zipper;
    private final String baseUrl;
    private final Scheduler scheduler;
    private final ZoneId zone;
    private final Clock clock;

    /**
     * Creates the nightly map-pack publisher for the given BlueMap tiles folder.
     *
     * @param tiles BlueMap's tiles folder of the play world, with level 1 below it
     */
    MapPacks(
            final Path tiles,
            final SettingStore settings,
            final PackShelf shelf,
            final MapZipper zipper,
            final String baseUrl,
            final Scheduler scheduler,
            final ZoneId zone,
            final Clock clock) {
        this.tiles = Objects.requireNonNull(tiles, "tiles");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.shelf = Objects.requireNonNull(shelf, "shelf");
        this.zipper = Objects.requireNonNull(zipper, "zipper");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns the map builds that put their packs on the shelf the language packs use.
     *
     * @param zone the network's zone, which {@link #AT} is in
     */
    public static MapPacks fromTiles(
            final Path tiles,
            final SettingStore settings,
            final Path shelf,
            final String baseUrl,
            final Scheduler scheduler,
            final ZoneId zone,
            final Clock clock)
            throws IOException {
        Files.createDirectories(shelf);
        return new MapPacks(
                tiles,
                settings,
                new PackShelf(shelf, PackBuilds.KEEP, clock),
                new ChildZipper(TIMEOUT),
                baseUrl,
                scheduler,
                zone,
                clock);
    }

    /** Schedules the nightly builds; the first runs soon when the proxy offers no map pack the shelf holds. */
    public void start() {
        schedule(missing() ? SOON : untilNext(clock.instant(), zone, AT));
    }

    private void schedule(final Duration delay) {
        scheduler.after(delay, () -> {
            try {
                build();
            } finally {
                schedule(untilNext(clock.instant(), zone, AT));
            }
        });
    }

    /** Returns the time from {@code now} to the next {@code at} in {@code zone}, never zero. */
    static Duration untilNext(final Instant now, final ZoneId zone, final LocalTime at) {
        final ZonedDateTime here = now.atZone(zone);
        ZonedDateTime next = here.with(at);
        if (!next.isAfter(here)) {
            next = here.plusDays(1).with(at);
        }
        return Duration.between(here, next);
    }

    /**
     * Builds the map pack and sets it, unless the proxy offers the same pack already.
     *
     * @return {@code true} if a new map pack was set
     */
    boolean build() {
        if (!Files.isDirectory(tiles.resolve("1"))) {
            log.info("map tiles missing, map build skipped: path={}", tiles);
            return false;
        }
        Path work = null;
        try {
            work = shelf.workspace();
            final Path zip = work.resolve("map.zip");
            zipper.map(tiles, zip);
            final byte[] bytes = Files.readAllBytes(zip);
            final String sha1 = ReproducibleZip.sha1(bytes);
            if (sha1.equals(PackState.readMap(settings).sha1()) && shelf.has(sha1)) {
                log.info("map unchanged, map pack kept: sha1={}", sha1);
                return false;
            }
            shelf.put(zip, sha1);
            PackWriter.writeMap(settings, PackShelf.url(baseUrl, sha1), sha1);
            log.info("map pack built: sha1={} bytes={}", sha1, bytes.length);
            shelf.prune(settings, sha1);
            return true;
        } catch (final IOException | RuntimeException failure) {
            log.warn("map pack build failed, active map pack kept: cause={}", failure.getMessage());
            return false;
        } finally {
            if (work != null) {
                PackShelf.deleteQuietly(work);
            }
        }
    }

    /** Returns whether the proxy offers no map pack the shelf holds, which a fresh stack or a lost shelf leaves. */
    private boolean missing() {
        try {
            final String sha1 = PackState.readMap(settings).sha1();
            return sha1 == null || !shelf.has(sha1);
        } catch (final RuntimeException unreadable) {
            log.warn("active map pack unreadable, build selected: cause={}", unreadable.getMessage());
            return true;
        }
    }
}
