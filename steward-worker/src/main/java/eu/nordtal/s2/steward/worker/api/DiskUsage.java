package eu.nordtal.s2.steward.worker.api;

import eu.nordtal.s2.steward.worker.plan.Topology;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * How much of the disk one service's volume takes, for the Disk field on its page.
 *
 * <p><b>Only the four services whose volume this container mounts have a number</b> - smp, proxy,
 * limbo and hunger-games, under {@code <volumes-root>/<name>}. postgres, caddy and the rest have no
 * volume here, and they get no field rather than a zero: 0 bytes would be a statement, and a false
 * one.
 *
 * <p><b>It is {@code du -sk}, not a walk in Java</b>, because the number on the page is meant to be
 * the one {@code du} prints on the host - allocated blocks, not the sum of file lengths. It follows
 * the {@code plugins/} mount beneath each volume, so the number is the world plus its jars.
 *
 * <p><b>Measured 2026-09-23 on the dev host:</b> {@code du -s} over all four volumes took 0.31 s,
 * smp alone being 4.7 GB. Cheap enough that the first ask may wait for it; every later one is handed
 * the last answer and a refresh starts beside it ({@link Refreshed}), so a world that has grown
 * until {@code du} is slow makes the number older, never the page slower. The age travels with it.
 */
final class DiskUsage {

    private static final Logger log = LoggerFactory.getLogger(DiskUsage.class);

    static final Duration TTL = Duration.ofMinutes(5);

    /** One measurement: the bytes, or none when {@code du} could not answer, and when it was taken. */
    record Measured(@NotNull OptionalLong bytes, @NotNull Instant at) {
    }

    private final @Nullable Path volumesRoot;
    private final Function<Path, OptionalLong> measure;
    private final Executor background;
    private final Supplier<Instant> clock;
    private final Map<String, Refreshed<Measured>> cache = new ConcurrentHashMap<>();

    DiskUsage(final @Nullable Path volumesRoot, final @NotNull Executor background) {
        this(volumesRoot, DiskUsage::du, background, Instant::now);
    }

    DiskUsage(final @Nullable Path volumesRoot, final @NotNull Function<Path, OptionalLong> measure,
              final @NotNull Executor background, final @NotNull Supplier<Instant> clock) {
        this.volumesRoot = volumesRoot;
        this.measure = measure;
        this.background = background;
        this.clock = clock;
    }

    /** The last measurement for {@code service}, or empty when it has no volume here. */
    @NotNull Optional<Measured> of(final @NotNull String service) {
        if (volumesRoot == null || !Topology.hasPlugins(service)) {
            return Optional.empty();
        }
        final Path volume = volumesRoot.resolve(service);
        if (!Files.isDirectory(volume)) {
            return Optional.empty();
        }
        final Measured measured = cache.computeIfAbsent(service, name -> new Refreshed<>(
                () -> new Measured(measure.apply(volume), clock.get()), TTL, background, clock)).get();
        return measured.bytes().isPresent() ? Optional.of(measured) : Optional.empty();
    }

    /** {@code du -sk}, in bytes. Empty when it fails or takes longer than half a minute. */
    static @NotNull OptionalLong du(final @NotNull Path path) {
        try {
            final Process process = new ProcessBuilder("du", "-sk", path.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("du on {} took longer than 30 s and was stopped", path);
                return OptionalLong.empty();
            }
            // du exits 1 when one file vanished under it, which a running server does all the
            // time; the total it printed is still the total.
            final String out = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            final int tab = out.indexOf('\t');
            if (tab <= 0) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(Long.parseLong(out.substring(0, tab)) * 1024L);
        } catch (IOException | NumberFormatException failed) {
            log.warn("du on {} failed: {}", path, failed.toString());
            return OptionalLong.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return OptionalLong.empty();
        }
    }
}
