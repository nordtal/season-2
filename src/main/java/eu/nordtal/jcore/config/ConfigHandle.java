package eu.nordtal.jcore.config;

import com.google.gson.Gson;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.config.exception.ConfigReadException;
import eu.nordtal.jcore.config.exception.ConfigValidationException;
import eu.nordtal.jcore.config.exception.ConfigWriteException;
import eu.nordtal.jcore.config.exception.UnknownConfigKeyException;
import eu.nordtal.jcore.config.internal.AtomicConfigWriter;
import eu.nordtal.jcore.config.internal.EnvOverlay;
import eu.nordtal.jcore.config.internal.SpecPaths;
import eu.nordtal.jcore.config.internal.UnknownKeyDetector;
import eu.nordtal.jcore.config.spec.ArrayCommentStyle;
import eu.nordtal.jcore.config.spec.CommentedConfiguration;
import eu.nordtal.jcore.config.spec.ManagedSpecReference;
import eu.nordtal.jcore.config.spec.SpecClass;
import eu.nordtal.jcore.config.spec.Specs;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * A live handle on one commented YAML configuration file.
 * <p>
 * Obtain one from {@link ConfigLoader}. {@link #get()} returns a stable instance of the spec
 * interface that always reads the current values, so it is safe to store in a field across a
 * reload.
 *
 * <h2>What a load does, in order</h2>
 * <ol>
 *   <li>write a defaults file if none exists;</li>
 *   <li>read the file and reject any key the spec does not declare, <b>without touching the
 *       file</b>;</li>
 *   <li>deserialize;</li>
 *   <li>if the canonical rendering differs from what is on disk - a new setting, a reworded
 *       comment, a changed header - back the file up to {@code .bak} and rewrite it atomically;</li>
 *   <li>apply the environment overlay <b>after</b> that write, so an overridden value can never
 *       reach the file;</li>
 *   <li>validate;</li>
 *   <li>run the load hook - <b>always</b>, whether or not anything changed.</li>
 * </ol>
 * Step 7 is where the old loader went wrong: it ran {@code postLoad()} only when the diff was
 * non-empty, so in the normal case of a file that already matched the class the hook never ran
 * at all.
 *
 * <h2>Threading</h2>
 * Reads through {@link #get()} are lock-free. {@link #reload()} and {@link #save()} take a write
 * lock that is shared by every handle on the same file, so a reload can never be observed
 * half-applied and two handles cannot write over each other.
 *
 * @param <T> the spec interface type
 */
public final class ConfigHandle<T> {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigHandle.class);

    /**
     * One lock per file, not per handle: two handles on the same path must serialise against
     * each other, which a per-instance lock would not do.
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

    ConfigHandle(final Path file, final Class<T> specType, final Gson gson, final EnvOverlay overlay,
                 final ConfigValidator<T> validator, final Consumer<T> onLoad) {
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
     */
    public @NotNull T get() {
        return reference.get();
    }

    /** The file this handle reads and writes. */
    public @NotNull Path file() {
        return file;
    }

    /**
     * The config paths whose value currently comes from an environment variable rather than the
     * file. The values themselves are not exposed; any of them could be a secret.
     */
    public @NotNull @Unmodifiable List<String> environmentOverrides() {
        return overriddenPaths;
    }

    /**
     * Re-reads the file. Applies the same sequence, and the same strictness, as the first load.
     *
     * @throws ConfigException if the file cannot be read, contains an unknown key, or fails
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
     * Writes the current values back to the file, atomically, preserving comments.
     * Environment-supplied values are restored to their file values first, so an override is
     * never persisted.
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
            final Map<String, Object> fileValues = fileValuesFor(value);
            try {
                // Put the file's own values back for every overridden path, write, then restore
                // the overrides in memory. Otherwise a password handed in through the
                // environment would be written into a mounted config volume.
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

    // ------------------------------------------------------------------------------------

    void loadInitially() throws ConfigException {
        lock.writeLock().lock();
        try {
            doLoad();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void doLoad() throws ConfigException {
        final boolean fresh = !Files.isRegularFile(file);
        final CommentedConfiguration configuration = newConfiguration();

        try {
            configuration.load();
        } catch (UncheckedIOException e) {
            throw new ConfigReadException("Cannot read config file " + file, e.getCause());
        } catch (RuntimeException e) {
            throw new ConfigReadException("Cannot parse config file " + file + ": " + e.getMessage(), e);
        }

        // Reject unknown keys before anything is written. The file is never trimmed to make it
        // match the spec - that is the operator's decision, not ours.
        final List<UnknownKeyDetector.UnknownKey> unknown =
                UnknownKeyDetector.detect(specType, configuration.getData());
        if (!unknown.isEmpty()) {
            throw new UnknownConfigKeyException(file, unknown);
        }

        final T value;
        try {
            value = configuration.getAs(specType);
        } catch (RuntimeException e) {
            throw new ConfigReadException("Cannot read config file " + file + " as "
                    + specType.getSimpleName() + ": " + e.getMessage(), e);
        }

        // Normalise: adds settings that were not in the file yet, refreshes comments and the
        // header, and fixes ordering. Only writes when the result actually differs.
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
            }
        } catch (UncheckedIOException e) {
            throw new ConfigWriteException(file, specType, e.getCause());
        }

        // After the write, so overrides never reach the file.
        final List<String> overridden;
        try {
            overridden = overlay.applyTo(value);
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException(file, e.getMessage(), e);
        }
        if (!overridden.isEmpty()) {
            // The paths, never the values - any one of them could be a secret.
            LOG.info("{}: {} setting(s) overridden by environment variables: {}",
                    file.getFileName(), overridden.size(), String.join(", ", overridden));
        }

        // Validate before the new value is published. Otherwise a reload that fails validation
        // would leave this handle holding values the application never accepted.
        try {
            validator.validate(value);
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException(file, e.getMessage(), e);
        }

        reference.set(value);
        this.overriddenPaths = List.copyOf(overridden);

        // Unconditional. Whether the file changed says nothing about whether the application
        // still needs its post-load wiring done.
        onLoad.accept(reference.get());
    }

    /** The values as they are (or would be) in the file, for the overridden paths only. */
    private Map<String, Object> fileValuesFor(final T value) throws ConfigException {
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
        final T stored = onDisk.getData().isEmpty() ? null : onDisk.getAs(specType);
        for (String path : overriddenPaths) {
            values.put(path, stored == null ? null : SpecPaths.get(stored, path));
        }
        return values;
    }

    private CommentedConfiguration configurationFor(final T value) {
        final CommentedConfiguration configuration = newConfiguration();
        configuration.setTo(value, specType);
        return configuration;
    }

    private CommentedConfiguration newConfiguration() {
        final CommentedConfiguration configuration =
                new CommentedConfiguration(file, gson, ArrayCommentStyle.COMMENT_FIRST_ELEMENT);
        final SpecClass spec = Specs.from(specType);
        configuration.setComments(spec.comments());
        configuration.setHeaders(spec.headers());
        return configuration;
    }

    private void write(final CommentedConfiguration configuration) {
        AtomicConfigWriter.backup(file);
        AtomicConfigWriter.write(file, configuration.render());
    }

    private String readOrEmpty() {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Bridges the spec's {@code @Reload} / {@code @Save} methods, which cannot declare a checked
     * exception, onto the checked API. A reload triggered from a command is still a failure the
     * caller has to see.
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
