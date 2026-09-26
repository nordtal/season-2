package eu.nordtal.s2.common.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * The neighbour file a service writes beside one of its own jcore-managed configs, naming which
 * dotted paths the environment currently overrides - so that steward-worker can tell an operator
 * "this key is overridden by the environment, editing it here has no effect until the variable is
 * removed" without depending on jcore's {@code ConfigHandle} or reimplementing its
 * {@code NORDTAL_<PREFIX>_<PATH>} naming rule a second time (steward/76).
 *
 * <p><b>jcore stays the only source of the list.</b> A service calls {@link #write} once per load
 * with exactly what {@code ConfigHandle.environmentOverrides()} already told it; nothing here
 * decides what counts as an override, and this class never looks at an environment variable
 * itself. That is the whole reason it lives in {@code :common} rather than being reimplemented in
 * every module that has a config: one routine, called from several places, beats one routine
 * copied into several places and only some of them updated the day the naming rule changes.</p>
 *
 * <p><b>The file's existence is itself a fact, and its absence is a different one.</b> A service
 * that has never called {@link #write} - one older than steward/76, or one this mechanism has not
 * reached yet - leaves no neighbour file behind, and {@link #read} says so with
 * {@link Optional#empty()} rather than an empty list: "nothing is overridden" and "nobody said"
 * are two different facts, and collapsing them into one is exactly the silent wrong answer
 * steward/76 exists to prevent. See {@code ConfigEntry#environmentOverridden()} in steward-worker,
 * which carries the same distinction one layer up as a {@code Boolean} that stays {@code null}
 * rather than defaulting to {@code false}.</p>
 *
 * <p><b>Deliberately not JSON.</b> {@code :common} depends on JDBI, HikariCP, slf4j-api and the
 * PostgreSQL driver and nothing else - pulling in a JSON library for one list of dotted paths
 * would be the wrong trade for what it buys. A dotted config path never contains a newline, so one
 * path per line is the whole format, and both sides only ever have to agree on that.</p>
 */
public final class EnvOverrideFile {

    /**
     * What every neighbour file's name ends in. Public because steward-worker has to recognise
     * these files in order to <b>not</b> list them as configurations of their own - a marker file
     * offered for editing beside the file it describes would be nonsense, and worse, editable
     * nonsense. One constant, so the writer and that filter cannot drift apart.
     */
    public static final @NotNull String SUFFIX = ".env-overrides.txt";

    private EnvOverrideFile() {}

    /**
     * The neighbour file for {@code configFile} - {@code access.yml} names
     * {@code access.env-overrides.txt}, beside it, the same way jcore's own schema writer names
     * {@code access.schema.json} beside the same file. The naming rule is duplicated here rather
     * than shared with jcore on purpose: it only decides where a file goes, never what counts as an
     * override, and jcore's own schema-naming method is not part of its public API besides.
     */
    public static @NotNull Path fileFor(final @NotNull Path configFile) {
        final String name = configFile.getFileName().toString();
        final String base;
        if (name.endsWith(".yml")) {
            base = name.substring(0, name.length() - ".yml".length());
        } else if (name.endsWith(".yaml")) {
            base = name.substring(0, name.length() - ".yaml".length());
        } else {
            base = name;
        }
        return configFile.resolveSibling(base + SUFFIX);
    }

    /**
     * Writes the paths currently supplied by the environment next to {@code configFile}, atomically
     * - one per line, in the order given. Called with an empty list rather than not called at all
     * when nothing is overridden, so the file's mere existence still says "this service did
     * report" - see the class doc for why that distinction matters.
     *
     * @throws IOException if the file cannot be written
     */
    public static void write(final @NotNull Path configFile, final @NotNull List<String> overriddenPaths)
            throws IOException {
        final Path target = fileFor(configFile);
        final String content = overriddenPaths.isEmpty() ? "" : String.join("\n", overriddenPaths) + "\n";
        final Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.writeString(
                tmp,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException notAtomic) {
            // Same fallback jcore's own AtomicConfigWriter takes: some filesystems (an overlay mount
            // across two volumes, some network filesystems) cannot rename atomically, and a plain
            // move is still far better than writing the target in place.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The paths {@code configFile}'s neighbour file names, or {@link Optional#empty()} when there
     * is none - see the class doc for why that is not the same as an empty list.
     *
     * @throws IOException if the file exists but cannot be read
     */
    public static @NotNull Optional<List<String>> read(final @NotNull Path configFile) throws IOException {
        final Path source = fileFor(configFile);
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        final List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
        return Optional.of(lines);
    }
}
