package eu.nordtal.s2.steward.configfile;

import eu.nordtal.s2.settings.EnvOverrideFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the {@code <name>.env-overrides.txt} a service writes beside its own config file.
 *
 * A missing file means the service never reported, so the entry stays {@code null} rather than {@code false}.
 */
final class EnvOverrides {

    private static final Logger LOG = LoggerFactory.getLogger(EnvOverrides.class);

    private EnvOverrides() {}

    /**
     * Reads the paths the environment overrides.
     *
     * @param ymlFile the configuration file
     * @return the dotted paths its neighbour file names, or empty when there is none to read
     */
    static Optional<Set<String>> read(final Path ymlFile) {
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
