package eu.nordtal.s2.steward.worker.configfile;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import eu.nordtal.jcore.config.schema.SchemaNode;
import eu.nordtal.jcore.config.schema.SchemaWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the {@code <name>.schema.json} jcore writes beside a config file, if there is one
 * (steward/54, steward/55).
 *
 * <p>{@link SchemaWriter#schemaFileFor} is jcore's own naming rule, reused here rather than
 * reimplemented: {@code access.yml} is described by {@code access.schema.json}, never a fixed
 * {@code config.schema.json} - several modules put more than one config file in one directory, and
 * a fixed name would silently overwrite itself there (Till, 2026-09-16). {@link SchemaNode} is
 * jcore's own record, deserialised straight out of the JSON it wrote - the shape it wrote is the
 * only description of that shape this class needs, and a hand-rolled reader would be a second copy
 * that drifts the day jcore's schema shape changes.</p>
 */
final class Schemas {

    private static final Logger LOG = LoggerFactory.getLogger(Schemas.class);
    private static final Gson GSON = new Gson();

    private Schemas() {}

    /**
     * The schema tree for {@code ymlFile}, if it has one.
     *
     * <p>A missing schema is not an error - a file jcore has not yet written under 4.0.0, or one
     * nothing ever wrote a schema for, is the second-choice case steward/55 keeps working exactly
     * as it did before this class existed. A schema file that exists but does not parse is treated
     * the same way, with a warning: the config file is still the truth (steward/50), and refusing to
     * show it because its schema is broken would be a worse failure than showing it without one.</p>
     *
     * @param ymlFile the configuration file
     * @return the root of its schema tree - always {@link eu.nordtal.jcore.config.schema.SettingKind#MAP} -
     *         or empty if there is none to read
     */
    static Optional<SchemaNode> read(final Path ymlFile) {
        final Path schemaFile = SchemaWriter.schemaFileFor(ymlFile);
        if (!Files.isRegularFile(schemaFile)) {
            return Optional.empty();
        }
        try {
            final String json = Files.readString(schemaFile, StandardCharsets.UTF_8);
            final SchemaNode node = GSON.fromJson(json, SchemaNode.class);
            if (node == null) {
                LOG.warn("{} is empty; reading {} without a schema.", schemaFile, ymlFile);
                return Optional.empty();
            }
            return Optional.of(node);
        } catch (final IOException | UncheckedIOException | JsonSyntaxException e) {
            LOG.warn("{} could not be read as a schema; reading {} without one.", schemaFile, ymlFile, e);
            return Optional.empty();
        }
    }
}
