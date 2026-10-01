package eu.nordtal.s2.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.jcore.config.spec.CommentedConfiguration;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/**
 * The settings files a process kept in its data folder before its settings lived in the database.
 *
 * Read once, at the first start that finds them, then deleted. This goes once every installation has started once.
 */
final class LegacyFiles {

    private final Path folder;
    private final Set<String> skipped;
    private final Logger logger;
    private final Set<String> unreadable = ConcurrentHashMap.newKeySet();

    /**
     * Returns the files in {@code folder}.
     *
     * @param skipped {@code group/path} of every value never imported, whatever the file says
     */
    LegacyFiles(final Path folder, final Set<String> skipped, final Logger logger) {
        this.folder = Objects.requireNonNull(folder, "folder");
        this.skipped = Set.copyOf(skipped);
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Returns every value of {@code <group>.yml} that differs from the spec's own default, as JSON text by path.
     *
     * @param excluded the paths never imported: secrets and what the environment holds
     */
    Map<String, String> changed(final Group<?> group, final Set<String> excluded) {
        final Path file = folder.resolve(group.name() + ".yml");
        final Map<String, String> changed = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return changed;
        }
        final JsonObject read;
        try {
            final CommentedConfiguration yaml = CommentedConfiguration.from(file, SpecJson.GSON);
            yaml.load();
            read = SpecJson.tree(
                    SpecJson.read(SpecJson.GSON.toJsonTree(yaml.getData()).getAsJsonObject(), group.spec()),
                    group.spec());
        } catch (final RuntimeException broken) {
            unreadable.add(file.getFileName().toString());
            logger.warn("{} is not imported and stays where it is: {}", file, broken.getMessage());
            return changed;
        }
        final JsonObject defaults = SpecJson.defaults(group.spec());
        for (final String path : SpecJson.leaves(group.spec())) {
            final JsonElement value = SpecJson.at(read, path);
            if (value == null
                    || excluded.contains(path)
                    || skipped.contains(group.name() + "/" + path)
                    || value.equals(SpecJson.at(defaults, path))) {
                continue;
            }
            changed.put(path, value.toString());
        }
        return changed;
    }

    /**
     * Deletes the file of every group in {@code groups}, with its schema, its marker and its backups.
     *
     * A broken file stays, and so does the file of a group nothing loads, which only a warning names.
     */
    void retire(final Set<String> groups) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, LegacyFiles::isSettingsFile)) {
            for (final Path file : files) {
                final String name = file.getFileName().toString();
                if (unreadable.contains(name)) {
                    continue;
                }
                if (!groups.contains(groupOf(name))) {
                    logger.warn("{} belongs to no group this process loads: it is neither imported nor deleted", file);
                    continue;
                }
                Files.delete(file);
                logger.info("{} is retired: its settings live in the database", file);
            }
        } catch (final IOException failed) {
            logger.warn("the settings files in {} could not all be retired: {}", folder, failed.getMessage());
        }
    }

    private static boolean isSettingsFile(final Path file) {
        final String name = file.getFileName().toString();
        return Files.isRegularFile(file)
                && (name.endsWith(".yml")
                        || name.contains(".yml.")
                        || name.endsWith(".schema.json")
                        || name.endsWith(".env-overrides.txt"));
    }

    /** Returns the group a settings file belongs to: {@code config} for {@code config.yml.bak}. */
    private static String groupOf(final String file) {
        for (final String suffix : new String[] {".yml", ".schema.json", ".env-overrides.txt"}) {
            final int at = file.indexOf(suffix);
            if (at > 0) {
                return file.substring(0, at);
            }
        }
        return file;
    }
}
