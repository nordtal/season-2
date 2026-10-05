package eu.nordtal.season.proxy.ping;

import com.velocitypowered.api.util.Favicon;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * The 64 x 64 icon the server browser shows, from {@code plugins/proxy/icon.png}.
 *
 * The built-in one is {@code pack.png} at every second pixel; any failure is a warning and no icon.
 */
public final class ServerIcon {

    /** Where the built-in icon sits in the jar, and the file's name in the data folder. */
    public static final String FILE_NAME = "icon.png";

    private ServerIcon() {}

    /** The icon for every ping, or empty when there is none to be had. */
    public static Optional<Favicon> load(final Path dataDirectory, final Logger logger) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        final Path file = dataDirectory.resolve(FILE_NAME);
        try {
            if (!Files.isRegularFile(file)) {
                seed(file);
            }
            return Optional.of(Favicon.create(file));
        } catch (final IOException | RuntimeException failure) {
            logger.warn(
                    "No server icon: {} could not be read as a 64x64 PNG ({}). The server browser "
                            + "shows the MOTD without one until it is replaced.",
                    file,
                    failure.toString());
            return Optional.empty();
        }
    }

    private static void seed(final Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (InputStream builtIn = ServerIcon.class.getResourceAsStream("/" + FILE_NAME)) {
            if (builtIn == null) {
                throw new IOException("the jar carries no " + FILE_NAME);
            }
            Files.copy(builtIn, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
