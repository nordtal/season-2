package eu.nordtal.s2.networkcontrol.ping;

import com.velocitypowered.api.util.Favicon;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;

/**
 * The 64 x 64 icon the server browser shows next to the MOTD.
 *
 * <p>The one surface of the first impression that needs no resource pack: a ping carries the icon
 * itself, base64, so it is seen by everybody who has the address and by nobody who has joined.
 * The file is {@code plugins/network-control/icon.png}; a first start copies the built-in one
 * there so that the operator finds a file to replace rather than a setting to discover. The
 * built-in is the pack's wordmark on a dark square, made with {@code sips} on 2026-09-06 - a
 * placeholder in the sense docs/presentation.md uses the word, and the point where "the logo at
 * 64 px" turns out unreadable is the point where a reduced mark replaces it, from the brush.</p>
 *
 * <p>Velocity refuses anything but 64 x 64 (`Favicon.create` throws), and a ping without an icon
 * is a perfectly good ping - so every failure here is a warning and an empty answer, never a proxy
 * that does not start.</p>
 */
public final class ServerIcon {

    /** Where the built-in icon sits in the jar, and what the file is called in the data folder. */
    public static final String FILE_NAME = "icon.png";

    private ServerIcon() {
    }

    /**
     * @param dataDirectory the plugin's data folder
     * @param logger        for the one warning a bad file produces
     * @return the icon to put in every ping, or empty when there is none to be had
     */
    public static Optional<Favicon> load(final Path dataDirectory, final Logger logger) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        final Path file = dataDirectory.resolve(FILE_NAME);
        try {
            if (!Files.isRegularFile(file)) {
                seed(file);
            }
            return Optional.of(Favicon.create(file));
        } catch (final IOException | RuntimeException failure) {
            logger.warn("No server icon: {} could not be read as a 64x64 PNG ({}). The server browser "
                    + "shows the MOTD without one until it is replaced.", file, failure.toString());
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
