package eu.nordtal.s2.commands;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Every {@link Target} is named in both languages, since the timeout sentence names the process it waited for. */
class TargetSchemaTest {

    @Test
    void everyTargetNamesAMessageKeyThatBothBundlesCarry() throws IOException {
        // The timeout sentence names the target process, so every target needs a key.
        final String en = bundle("messages/commands/en.properties");
        final String de = bundle("messages/commands/de.properties");

        for (final Target target : Target.values()) {
            assertTrue(
                    en.contains(target.message().key() + "="),
                    target.message().key() + " is not in the English bundle");
            assertTrue(
                    de.contains(target.message().key() + "="), target.message().key() + " is not in the German bundle");
        }
    }

    private static String bundle(final String path) throws IOException {
        try (InputStream stream = TargetSchemaTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, path + " is not on the classpath");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
