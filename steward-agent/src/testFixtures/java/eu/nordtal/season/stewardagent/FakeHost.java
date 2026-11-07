package eu.nordtal.season.stewardagent;

import eu.nordtal.season.stewardagent.measure.HostMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A host with fixed numbers, as the kernel's files would report them, for an agent that runs on a machine of no use.
 *
 * The CPU counters keep growing at the share asked for, so every reading after the first has a percent.
 */
public final class FakeHost implements AutoCloseable {

    /** The numbers to report, memory and disk in bytes. */
    public record Spec(
            int cpus,
            double cpuPercent,
            double load,
            long memoryTotal,
            long memoryUsed,
            long diskTotal,
            long diskUsed) {}

    private static final long JIFFIES_PER_SECOND = 100;

    /** Counters start here, so the first reading has something to subtract from. */
    private static final long START_SECONDS = 1_000_000;

    private final Path proc;
    private final Spec spec;
    private final HostMetrics metrics;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final long startedNanos = System.nanoTime();

    /**
     * Writes the files under {@code directory} and reads them once, so the agent's first reading has a CPU share.
     */
    public FakeHost(final Path directory, final Spec spec) throws IOException {
        this.proc = Files.createDirectories(directory);
        this.spec = spec;
        this.metrics = new HostMetrics(
                proc,
                () -> new HostMetrics.Space(spec.diskTotal(), spec.diskUsed(), spec.diskTotal() - spec.diskUsed()));
        Files.writeString(proc.resolve("meminfo"), """
                MemTotal: %d kB
                MemFree: %d kB
                MemAvailable: %d kB
                SwapTotal: 0 kB
                SwapFree: 0 kB
                """.formatted(
                        spec.memoryTotal() / 1024,
                        (spec.memoryTotal() - spec.memoryUsed()) / 2048,
                        (spec.memoryTotal() - spec.memoryUsed()) / 1024));
        Files.writeString(
                proc.resolve("loadavg"),
                String.format(Locale.ROOT, "%.2f %.2f %.2f 1/400 1234%n", spec.load(), spec.load(), spec.load()));
        writeStat(START_SECONDS);
        metrics.read();
        writeStat(START_SECONDS + 30);
        Thread.ofPlatform().daemon().name("fake-host").start(this::tick);
    }

    /** The reader of this host, for {@link AgentStandIn}. */
    public HostMetrics metrics() {
        return metrics;
    }

    private void tick() {
        while (open.get()) {
            try {
                Thread.sleep(1000);
                writeStat(START_SECONDS + 30 + (System.nanoTime() - startedNanos) / 1_000_000_000L);
            } catch (final InterruptedException interrupted) {
                return;
            } catch (final IOException failed) {
                return;
            }
        }
    }

    private void writeStat(final long seconds) throws IOException {
        final long total = seconds * JIFFIES_PER_SECOND;
        final long busy = Math.round(total * spec.cpuPercent() / 100.0);
        final StringBuilder text = new StringBuilder();
        text.append(line("cpu ", spec.cpus() * busy, spec.cpus() * (total - busy)));
        for (int i = 0; i < spec.cpus(); i++) {
            text.append(line("cpu" + i + " ", busy, total - busy));
        }
        // Moved into place, so the agent never reads a half-written file.
        final Path temporary = proc.resolve("stat.tmp");
        Files.writeString(temporary, text.toString());
        Files.move(
                temporary, proc.resolve("stat"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String line(final String name, final long busy, final long idle) {
        return name + " " + busy + " 0 0 " + idle + " 0 0 0 0 0 0\n";
    }

    @Override
    public void close() {
        open.set(false);
    }
}
