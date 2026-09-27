package eu.nordtal.s2.steward.worker.api;

import eu.nordtal.s2.steward.worker.plan.Topology;
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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How much of the disk one service's volume takes, by {@code du -sk}, for the Disk field on its page.
 *
 * Only services whose volume is mounted here get a number; after the first, each ask gets the last one and a refresh.
 */
final class DiskUsage {

    private static final Logger log = LoggerFactory.getLogger(DiskUsage.class);

    static final Duration TTL = Duration.ofMinutes(5);

    /** One measurement: the bytes, or none when {@code du} could not answer, and when it was taken. */
    record Measured(OptionalLong bytes, Instant at) {}

    private final @Nullable Path volumesRoot;
    private final Function<Path, OptionalLong> measure;
    private final Executor background;
    private final Supplier<Instant> clock;
    private final Map<String, Refreshed<Measured>> cache = new ConcurrentHashMap<>();

    DiskUsage(final @Nullable Path volumesRoot, final Executor background) {
        this(volumesRoot, DiskUsage::du, background, Instant::now);
    }

    DiskUsage(
            final @Nullable Path volumesRoot,
            final Function<Path, OptionalLong> measure,
            final Executor background,
            final Supplier<Instant> clock) {
        this.volumesRoot = volumesRoot;
        this.measure = measure;
        this.background = background;
        this.clock = clock;
    }

    /** The last measurement for {@code service}, or empty when it has no volume here. */
    Optional<Measured> of(final String service) {
        if (volumesRoot == null || !Topology.hasPlugins(service)) {
            return Optional.empty();
        }
        final Path volume = volumesRoot.resolve(service);
        if (!Files.isDirectory(volume)) {
            return Optional.empty();
        }
        final Measured measured = cache.computeIfAbsent(
                        service,
                        name -> new Refreshed<>(
                                () -> new Measured(measure.apply(volume), clock.get()), TTL, background, clock))
                .get();
        return measured.bytes().isPresent() ? Optional.of(measured) : Optional.empty();
    }

    /** {@code du -sk}, in bytes; empty when it fails or takes longer than half a minute. */
    static OptionalLong du(final Path path) {
        try {
            final Process process = new ProcessBuilder("du", "-sk", path.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("du on {} took longer than 30 s and was stopped", path);
                return OptionalLong.empty();
            }
            // du exits 1 when a file vanished under it, which a running server does all the time; the total stands.
            final String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
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
