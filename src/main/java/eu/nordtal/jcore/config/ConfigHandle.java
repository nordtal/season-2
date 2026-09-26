package eu.nordtal.jcore.config;

import com.google.gson.Gson;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.config.exception.ConfigReadException;
import eu.nordtal.jcore.config.exception.ConfigValidationException;
import eu.nordtal.jcore.config.exception.ConfigWriteException;
import eu.nordtal.jcore.config.exception.UnknownConfigKeyException;
import eu.nordtal.jcore.config.internal.EnvOverlay;
import eu.nordtal.jcore.config.internal.SpecPaths;
import eu.nordtal.jcore.config.internal.UnknownKeyDetector;
import eu.nordtal.jcore.config.schema.SchemaWriter;
import eu.nordtal.jcore.config.spec.ArrayCommentStyle;
import eu.nordtal.jcore.config.spec.CommentedConfiguration;
import eu.nordtal.jcore.config.spec.ManagedSpecReference;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A live handle on one YAML configuration file and its schema.
 *
 * Obtain one from {@link ConfigLoader}. {@link #get()} returns a stable instance of the spec
 * interface that always reads the current values, so it is safe to store in a field across a
 * reload.
 *
 * <b>What a load does, in order:</b> (1) write a defaults file if none exists; (2) read the file
 * and reject any key that reads as a <i>mistyped</i> declared setting, <b>without touching the
 * file</b> - a key that resembles nothing declared is a setting the spec has since dropped, and it
 * is logged and removed by the write in step 4; (3) deserialize; (4) if the canonical rendering
 * differs from what is on disk - a new setting or a value normalised - back the file up to
 * {@code .bak} and rewrite it atomically. The YAML carries no comments; see
 * {@link eu.nordtal.jcore.config.schema.SchemaWriter} for where the explanations went; (5) write
 * this spec's {@code config.schema.json} beside the file, every time, whether or not step 4
 * changed anything - a {@code @Explain} or {@code @AllowedValues} edit never changes the rendered
 * YAML, but it must never leave the schema stale either; (6) apply the environment overlay
 * <b>after</b> that write, so an overridden value can never reach the file; (7) validate; (8) run
 * the load hook - <b>always</b>, whether or not anything changed.
 * The load hook step is where the old loader went wrong: it ran {@code postLoad()} only when the
 * diff was non-empty, so in the normal case of a file that already matched the class the hook
 * never ran at all.
 *
 * <b>Threading:</b> reads through {@link #get()} are lock-free. {@link #reload()} and
 * {@link #save()} take a write
 * lock that is shared by every handle on the same file, so a reload can never be observed
 * half-applied and two handles cannot write over each other.
 *
 * @param <T> the spec interface type
 */
public final class ConfigHandle<T> {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigHandle.class);

    /**
     * One lock per file, not per handle, so two handles on the same path still serialise against each other.
     */
    private static final Map<Path, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<>();

    private final Path file;
    private final Class<T> specType;
    private final Gson gson;
    private final EnvOverlay overlay;
    private final ConfigValidator<T> validator;
    private final Consumer<T> onLoad;
    private final ReentrantReadWriteLock lock;
    private final ManagedSpecReference<T> reference;

    /** Config paths currently supplied by the environment. Never written back to the file. */
    private volatile List<String> overriddenPaths = List.of();

    ConfigHandle(
            final Path file,
            final Class<T> specType,
            final Gson gson,
            final EnvOverlay overlay,
            final ConfigValidator<T> validator,
            final Consumer<T> onLoad) {
        this.file = file.toAbsolutePath().normalize();
        this.specType = specType;
        this.gson = gson;
        this.overlay = overlay;
        this.validator = validator;
        this.onLoad = onLoad;
        this.lock = LOCKS.computeIfAbsent(this.file, path -> new ReentrantReadWriteLock());
        this.reference = new ManagedSpecReference<>(specType, this::reloadUnchecked, this::saveUnchecked);
    }

    /**
     * The configuration. The returned instance is stable across reloads - keep it in a field.
     *
     * @return the stable spec instance
     */
    public T get() {
        return reference.get();
    }

    /**
     * The file this handle reads and writes.
     *
     * @return the config file
     */
    public Path file() {
        return file;
    }

    /**
     * The config paths whose value currently comes from an environment variable rather than the file.
     *
     * The values themselves are not exposed; any of them could be a secret.
     *
     * @return the overridden config paths
     */
    public List<String> environmentOverrides() {
        return overriddenPaths;
    }

    /**
     * Re-reads the file. Applies the same sequence, and the same strictness, as the first load.
     *
     * @throws ConfigException if the file cannot be read, contains a mistyped setting, or fails
     *                         validation. On failure the previously loaded values stay in place.
     */
    public void reload() throws ConfigException {
        lock.writeLock().lock();
        try {
            doLoad();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Writes the current values back to the file, atomically, and refreshes its schema.
     *
     * Environment-supplied values are restored to their file values first, so an override is never persisted.
     *
     * @throws ConfigException if the file cannot be written
     */
    public void save() throws ConfigException {
        lock.writeLock().lock();
        try {
            final T value = reference.current();
            if (value == null) {
                throw new ConfigException("Cannot save " + file + ": it has not been loaded yet.");
            }
            final Map<String, Object> overridden = SpecPaths.snapshot(value, overriddenPaths);
            final Map<String, Object> fileValues = fileValuesFor();
            try {
                // Restore file values before writing, or an environment-supplied password reaches the volume.
                fileValues.forEach((path, fileValue) -> SpecPaths.set(value, path, fileValue));
                write(configurationFor(value));
            } finally {
                overridden.forEach((path, override) -> SpecPaths.set(value, path, override));
            }
        } catch (UncheckedIOException e) {
            throw new ConfigWriteException(file, specType, e.getCause());
        } finally {
            lock.writeLock().unlock();
        }
    }

    void loadInitially() throws ConfigException {
        lock.writeLock().lock();
        try {
            doLoad();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** {@link #doLoad}'s deserialized value together with the settings the spec no longer declares. */
    private record ParsedValue<V>(V value, List<String> retired) {}

    private void doLoad() throws ConfigException {
        final boolean fresh = !Files.isRegularFile(file);
        final CommentedConfiguration configuration = newConfiguration();

        readConfiguration(configuration);

        final ParsedValue<T> parsed = parseValue(configuration);
        final T value = parsed.value();

        normalizeAndWrite(configuration, value, parsed.retired(), fresh);

        // Unconditional: an @Explain/@AllowedValues/header edit changes the schema without changing the YAML.
        try {
            SchemaWriter.write(file, specType);
        } catch (UncheckedIOException e) {
            throw new ConfigWriteException(file, specType, e.getCause());
        }
        SchemaWriter.checkPaired(file);

        final List<String> overridden = applyOverlay(value);
        validateValue(value);

        reference.set(value);
        this.overriddenPaths = List.copyOf(overridden);

        // Unconditional: whether the file changed says nothing about whether post-load wiring is still needed.
        onLoad.accept(reference.get());
    }

    private void readConfiguration(final CommentedConfiguration configuration) throws ConfigException {
        try {
            configuration.load();
        } catch (UncheckedIOException e) {
            throw new ConfigReadException("Cannot read config file " + file, e.getCause());
        } catch (RuntimeException e) {
            throw new ConfigReadException("Cannot parse config file " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * A key the spec does not declare is either a typo of a declared key or a retired setting.
     *
     * A probable typo refuses the load and leaves the file untouched, since only the operator knows what they
     * meant. A retired key has nothing to fix, so it is dropped by the write below, named in the log, and kept
     * in the {@code .bak}.
     */
    private ParsedValue<T> parseValue(final CommentedConfiguration configuration) throws ConfigException {
        final List<UnknownKeyDetector.UnknownKey> unknown =
                UnknownKeyDetector.detect(specType, configuration.getData());
        final List<UnknownKeyDetector.UnknownKey> mistyped = unknown.stream()
                .filter(UnknownKeyDetector.UnknownKey::probableTypo)
                .toList();
        if (!mistyped.isEmpty()) {
            throw new UnknownConfigKeyException(file, mistyped);
        }
        final List<String> retired =
                unknown.stream().map(UnknownKeyDetector.UnknownKey::path).toList();

        final T value;
        try {
            value = Objects.requireNonNull(
                    specType.cast(configuration.getAs(specType)), "a loaded config is never JSON null");
        } catch (RuntimeException e) {
            throw new ConfigReadException(
                    "Cannot read config file " + file + " as " + specType.getSimpleName() + ": " + e.getMessage(), e);
        }
        return new ParsedValue<>(value, retired);
    }

    /** Adds settings missing from the file and fixes ordering. Writes only when the result actually differs. */
    private void normalizeAndWrite(
            final CommentedConfiguration configuration, final T value, final List<String> retired, final boolean fresh)
            throws ConfigException {
        try {
            configuration.setTo(value, specType);
            final String rendered = configuration.render();
            if (fresh || !rendered.equals(readOrEmpty())) {
                if (!fresh) {
                    AtomicConfigWriter.backup(file);
                }
                AtomicConfigWriter.write(file, rendered);
                if (fresh) {
                    LOG.info("Created config file {} with its default values.", file);
                } else {
                    LOG.info("Config file {} was brought up to date; the previous content is in {}.bak", file, file);
                }
                if (!retired.isEmpty()) {
                    // Log the paths, never the values: a retired setting can still have been a password.
                    LOG.warn(
                            "{}: {} setting(s) no longer exist and were removed from the file: {}."
                                    + " They are still in {}.bak if you need what they said.",
                            file.getFileName(),
                            retired.size(),
                            String.join(", ", retired),
                            file);
                }
            }
        } catch (UncheckedIOException e) {
            throw new ConfigWriteException(file, specType, e.getCause());
        }
    }

    /** Applies the environment overlay, after the file write above so overrides never reach the file. */
    private List<String> applyOverlay(final T value) throws ConfigException {
        final List<String> overridden;
        try {
            overridden = overlay.applyTo(value);
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException(file, String.valueOf(e.getMessage()), e);
        }
        if (!overridden.isEmpty()) {
            // The paths, never the values - any one of them could be a secret.
            LOG.info(
                    "{}: {} setting(s) overridden by environment variables: {}",
                    file.getFileName(),
                    overridden.size(),
                    String.join(", ", overridden));
        }
        return overridden;
    }

    /** Validates before the new value is published, so a failed reload never leaves values the application rejected. */
    private void validateValue(final T value) throws ConfigException {
        try {
            validator.validate(value);
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException(file, String.valueOf(e.getMessage()), e);
        }
    }

    /** The values as they are (or would be) in the file, for the overridden paths only. */
    private Map<String, Object> fileValuesFor() throws ConfigException {
        if (overriddenPaths.isEmpty()) {
            return Map.of();
        }
        final CommentedConfiguration onDisk = newConfiguration();
        try {
            onDisk.load();
        } catch (RuntimeException e) {
            throw new ConfigReadException("Cannot re-read config file " + file, e);
        }
        final Map<String, Object> values = new LinkedHashMap<>();
        final @Nullable T stored = onDisk.getData().isEmpty() ? null : specType.cast(onDisk.getAs(specType));
        for (final String path : overriddenPaths) {
            values.put(path, stored == null ? null : SpecPaths.get(stored, path));
        }
        return values;
    }

    private CommentedConfiguration configurationFor(final T value) {
        final CommentedConfiguration configuration = newConfiguration();
        configuration.setTo(value, specType);
        return configuration;
    }

    /**
     * A fresh, empty configuration for {@link #file}.
     *
     * Carries no comments and no header: {@link SchemaWriter} puts {@code @Explain} onto each setting's
     * node and {@code @ConfigSpec(header = {...})} onto the root's explanation.
     */
    private CommentedConfiguration newConfiguration() {
        return new CommentedConfiguration(file, gson, ArrayCommentStyle.COMMENT_FIRST_ELEMENT);
    }

    private void write(final CommentedConfiguration configuration) throws ConfigException {
        AtomicConfigWriter.backup(file);
        AtomicConfigWriter.write(file, configuration.render());
        SchemaWriter.write(file, specType);
        SchemaWriter.checkPaired(file);
    }

    private String readOrEmpty() {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Bridges the spec's {@code @Reload}/{@code @Save} methods, which cannot declare a checked exception.
     *
     * A reload triggered from a command is still a failure the caller has to see.
     */
    private void reloadUnchecked() {
        try {
            reload();
        } catch (ConfigException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private void saveUnchecked() {
        try {
            save();
        } catch (ConfigException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
