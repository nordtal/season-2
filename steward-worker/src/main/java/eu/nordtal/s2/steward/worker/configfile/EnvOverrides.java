package eu.nordtal.s2.steward.worker.configfile;

import eu.nordtal.s2.common.config.EnvOverrideFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the {@code <name>.env-overrides.txt} a service writes beside its own config file
 * (steward/76), the same way {@link Schemas} reads that service's {@code <name>.schema.json} -
 * {@link eu.nordtal.s2.common.config.EnvOverrideFile} owns the naming rule and the format; this
 * class only adapts its {@code List} into the {@code Set} {@link ConfigFiles#collect} tests
 * membership against, and turns a read failure into "nothing to report" instead of a broken page.
 *
 * <p><b>Absent is not the same as empty</b> (see {@code EnvOverrideFile}'s own doc): a missing file
 * means the service behind {@code ymlFile} predates steward/76 or has not reloaded since, and
 * {@link ConfigEntry#environmentOverridden()} has to stay {@code null} for it rather than
 * {@code false} - the silent wrong answer this mechanism exists to prevent.</p>
 */
final class EnvOverrides {

    private static final Logger LOG = LoggerFactory.getLogger(EnvOverrides.class);

    private EnvOverrides() {}

    /**
     * @param ymlFile the configuration file
     * @return the dotted paths its neighbour file names, or empty when there is none to read - the
     *         same "no schema yet" shape {@link Schemas#read} answers with, for the same reason
     */
    static @NotNull Optional<Set<String>> read(final @NotNull Path ymlFile) {
        try {
            return EnvOverrideFile.read(ymlFile).map(Set::copyOf);
        } catch (final IOException e) {
            LOG.warn(
                    "{} could not be read as an environment-override marker; showing {} as though"
                            + " no service had reported one for it.",
                    EnvOverrideFile.fileFor(ymlFile),
                    ymlFile,
                    e);
            return Optional.empty();
        }
    }
}
