package eu.nordtal.s2.steward.worker.configfile;

import com.google.gson.JsonSyntaxException;
import eu.nordtal.jcore.config.schema.SchemaNode;
import eu.nordtal.jcore.config.schema.SchemaWriter;
import eu.nordtal.s2.common.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads the schema jcore writes beside a config file, named by jcore's own {@link SchemaWriter#schemaFileFor}. */
final class Schemas {

    private static final Logger LOG = LoggerFactory.getLogger(Schemas.class);

    private Schemas() {}

    /**
     * The schema tree for {@code ymlFile}; a missing or unreadable schema gives empty, the latter with a warning.
     *
     * @param ymlFile the configuration file
     * @return the root of its schema tree, always a {@link eu.nordtal.jcore.config.schema.SettingKind#MAP}, or empty
     */
    static Optional<SchemaNode> read(final Path ymlFile) {
        final Path schemaFile = SchemaWriter.schemaFileFor(ymlFile);
        if (!Files.isRegularFile(schemaFile)) {
            return Optional.empty();
        }
        try {
            final String json = Files.readString(schemaFile, StandardCharsets.UTF_8);
            final SchemaNode node = Json.decode(json, SchemaNode.class);
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
