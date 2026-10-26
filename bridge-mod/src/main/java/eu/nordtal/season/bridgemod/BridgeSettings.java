package eu.nordtal.season.bridgemod;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Properties;

/**
 * The port and the token the Nordtal Designer writes into an instance's config folder.
 * The token is read again for every connection.
 */
record BridgeSettings(int port, String token) {

    /** The file's name inside the instance's config folder. */
    static final String FILE = "nordtal-bridge.properties";

    /** Reads the file, or throws an IllegalArgumentException that names the wrong or missing line. */
    static BridgeSettings read(final Path file) throws IOException {
        final Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        final String token = properties.getProperty("token", "").strip();
        if (token.length() < 16) {
            throw new IllegalArgumentException(file + " needs a token of at least 16 characters.");
        }
        final String port = properties.getProperty("port", "").strip();
        try {
            final int number = Integer.parseInt(port);
            if (number < 1024 || number > 65535) {
                throw new NumberFormatException();
            }
            return new BridgeSettings(number, token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(file + " needs a port from 1024 to 65535, not '" + port + "'.", e);
        }
    }

    /** Whether the offered token is the one in the file now; a file that cannot be read accepts none. */
    static boolean accepts(final Path file, final String offered) {
        try {
            final byte[] expected = read(file).token().getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, offered.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }
}
