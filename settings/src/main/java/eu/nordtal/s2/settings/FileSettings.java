package eu.nordtal.s2.settings;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * Settings read from commented YAML files in one folder, each value overridable from the environment.
 * The main group takes the bare prefix, every other one {@code <prefix>_<NAME>}; a missing file gets the defaults.
 */
public final class FileSettings implements Settings {

    private final Path folder;
    private final String prefix;
    private final String main;
    private final Logger logger;

    private FileSettings(final Path folder, final String prefix, final String main, final Logger logger) {
        this.folder = Objects.requireNonNull(folder, "folder");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.main = Objects.requireNonNull(main, "main");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Returns the settings in {@code folder} whose main group is {@code config}. */
    public static FileSettings in(final Path folder, final String prefix, final Logger logger) {
        return in(folder, prefix, "config", logger);
    }

    /**
     * Returns the settings in {@code folder}.
     *
     * @param prefix the environment prefix, {@code NORDTAL_SMP} for example
     * @param main   the group whose values take the bare prefix
     */
    public static FileSettings in(final Path folder, final String prefix, final String main, final Logger logger) {
        return new FileSettings(folder, prefix, main, logger);
    }

    /** Returns the environment prefix of a group: the bare prefix for the main one. */
    public String prefixOf(final String name) {
        return name.equals(main)
                ? prefix
                : prefix + "_" + name.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    @Override
    public <T> Setting<T> load(final String name, final Class<T> spec, final Check<T> check) throws SettingsException {
        final Path file = folder.resolve(name + ".yml");
        final boolean fresh = !Files.isRegularFile(file);
        final ConfigHandle<T> handle;
        try {
            handle = ConfigLoader.builder(file, spec)
                    .envPrefix(prefixOf(name))
                    .validator(check::check)
                    .load();
        } catch (final ConfigException | IllegalArgumentException refused) {
            throw new SettingsException(name + ".yml: " + refused.getMessage(), refused);
        }
        if (fresh && name.equals(main)) {
            logger.warn(
                    "No config existed at {}: defaults were written and are almost certainly not what you want", file);
        } else if (fresh) {
            logger.info("No config existed at {}: it was written with this project's defaults", file);
        }
        try {
            EnvOverrideFile.write(handle.file(), handle.environmentOverrides());
        } catch (final IOException e) {
            logger.warn("Could not write the environment-override marker beside {}: {}", handle.file(), e.getMessage());
        }
        return new FileSetting<>(name, handle);
    }

    private record FileSetting<T>(String name, ConfigHandle<T> handle) implements Setting<T> {

        @Override
        public T get() {
            return handle.get();
        }

        @Override
        public void reload() throws SettingsException {
            try {
                handle.reload();
            } catch (final ConfigException | IllegalArgumentException refused) {
                throw new SettingsException(name + ".yml: " + refused.getMessage(), refused);
            }
        }
    }
}
