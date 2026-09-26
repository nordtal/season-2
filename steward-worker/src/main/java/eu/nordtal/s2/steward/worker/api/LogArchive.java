package eu.nordtal.s2.steward.worker.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The lines of earlier runs, out of the server's own rotated logs.
 *
 * <p>A Minecraft server writes {@code logs/latest.log} into its data volume and rotates it to
 * {@code 2026-09-23-2.log.gz} when it starts again. The volume survives every recreate and the
 * container's Docker log does not, so this is where the console finds what came before.
 *
 * <p><b>Which archives: the ones older than what Docker still holds.</b> A rotation happens when
 * the NEXT run starts, so an archive's mtime is the start of the run after the one inside it.
 * Anything rotated after the oldest line Docker has is a run Docker has too - measured 2026-09-23 on
 * smp, whose container held two runs, 22 Sep 19:44 and a restart on the 23rd, and whose
 * {@code 2026-09-23-2.log.gz} held the first of them again. The {@link #SLACK} covers the few
 * seconds between the container's first line and Paper getting round to its rotation.
 * {@code latest.log} is never read: it is the run Docker is showing.
 *
 * <p><b>Fast enough to read in full, measured 2026-09-23</b>: all 94 of smp's archives, 68 000
 * lines, decompress in 0.07 s. The ticket's fallback - only the newest archive - is still here, as
 * a {@link #BUDGET} on the walk, because a grown world is another matter.
 */
final class LogArchive {

    private static final Logger log = LoggerFactory.getLogger(LogArchive.class);

    static final Duration SLACK = Duration.ofMinutes(5);
    static final Duration BUDGET = Duration.ofSeconds(1);

    /** {@code 2026-09-23-2.log.gz}: log4j's rotated name, the date the file was started on. */
    private static final Pattern NAME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})-(\\d+)\\.log\\.gz");

    private static final Pattern CLOCK = Pattern.compile("^\\[(\\d{2}):(\\d{2}):\\d{2}]");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /** One earlier run, oldest line first, and the grey line that stands above it. */
    record Run(String label, List<String> lines) {}

    /** What the archive gave, oldest run first; {@code exhausted} when nothing older is left. */
    record Backlog(List<Run> runs, boolean exhausted) {
        int lineCount() {
            return runs.stream().mapToInt(run -> run.lines().size()).sum();
        }
    }

    private final @Nullable Path volumesRoot;
    private final Supplier<Instant> clock;

    LogArchive(final @Nullable Path volumesRoot) {
        this(volumesRoot, Instant::now);
    }

    LogArchive(final @Nullable Path volumesRoot, final Supplier<Instant> clock) {
        this.volumesRoot = volumesRoot;
        this.clock = clock;
    }

    /**
     * Up to {@code wanted} lines from the runs before {@code oldest}, newest run first while
     * reading and oldest first in the answer.
     */
    Backlog before(final String service, final Instant oldest, final int wanted) {
        final List<Path> archives = archives(service, oldest);
        final Deque<Run> runs = new ArrayDeque<>();
        int missing = wanted;
        final Instant started = clock.get();
        boolean exhausted = true;
        for (int index = 0; index < archives.size(); index++) {
            if (missing <= 0) {
                exhausted = false;
                break;
            }
            if (index > 0 && Duration.between(started, clock.get()).compareTo(BUDGET) > 0) {
                log.warn(
                        "reading the archived logs of {} took over {}; stopped after {} file(s)",
                        service,
                        BUDGET,
                        index);
                exhausted = false;
                break;
            }
            final Path archive = archives.get(index);
            final List<String> lines = read(archive);
            if (lines.isEmpty()) {
                continue;
            }
            final List<String> kept =
                    lines.size() > missing ? lines.subList(lines.size() - missing, lines.size()) : lines;
            runs.addFirst(new Run(label(archive, lines), List.copyOf(kept)));
            missing -= kept.size();
            if (lines.size() > kept.size()) {
                exhausted = false;
            }
        }
        return new Backlog(List.copyOf(runs), exhausted);
    }

    /** The rotated archives of this service from before {@code oldest}, newest first. */
    List<Path> archives(final String service, final Instant oldest) {
        if (volumesRoot == null) {
            return List.of();
        }
        final Path logs = volumesRoot.resolve(service).resolve("logs");
        if (!Files.isDirectory(logs)) {
            return List.of();
        }
        final Instant limit = oldest.plus(SLACK);
        try (Stream<Path> files = Files.list(logs)) {
            return files.filter(
                            file -> NAME.matcher(file.getFileName().toString()).matches())
                    .filter(file -> modified(file).isBefore(limit))
                    .sorted(Comparator.comparing(LogArchive::modified).reversed())
                    .toList();
        } catch (IOException e) {
            log.warn("could not list {}: {}", logs, e.toString());
            return List.of();
        }
    }

    private static Instant modified(final Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return Instant.MAX;
        }
    }

    private static List<String> read(final Path archive) {
        final List<String> lines = new ArrayList<>();
        final var decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(Files.newInputStream(archive)), decoder))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            log.warn("could not read {}: {}", archive, e.toString());
        }
        return lines;
    }

    /** "Earlier run, 22 Sep 19:44": the day from the name, the time from its first stamped line. */
    static String label(final Path archive, final List<String> lines) {
        final Matcher name = NAME.matcher(archive.getFileName().toString());
        final StringBuilder label = new StringBuilder("Earlier run");
        if (name.matches()) {
            label.append(", ").append(LocalDate.parse(name.group(1)).format(DAY));
            for (final String line : lines) {
                final Matcher clock = CLOCK.matcher(line);
                if (clock.find()) {
                    label.append(' ').append(clock.group(1)).append(':').append(clock.group(2));
                    break;
                }
            }
        }
        return label.toString();
    }
}
