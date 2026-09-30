package eu.nordtal.s2.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@link Target} and the {@code CHECK} on {@code command_request.target} are one fact in two places.
 *
 * The migration is read off the classpath, so this checks the file that would actually be applied.
 */
class TargetSchemaTest {

    private static final String MIGRATION = "db/migration/V1__schema.sql";

    private static String sql() throws IOException {
        try (InputStream stream = TargetSchemaTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertNotNull(
                    stream,
                    MIGRATION + " is not on the classpath - :database's resources are"
                            + " what put it there, so either the migration moved or the dependency did");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyTargetIsPermittedByTheCheckAndTheCheckPermitsNothingElse() throws IOException {
        final Matcher check = Pattern.compile("CHECK\\s*\\(target IN \\(([^)]*)\\)\\)", Pattern.CASE_INSENSITIVE)
                .matcher(sql());
        assertTrue(check.find(), "no CHECK on command_request.target in " + MIGRATION);

        final Set<String> permitted = Arrays.stream(check.group(1).split(","))
                .map(value -> value.trim().replace("'", ""))
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        final Set<String> declared =
                Arrays.stream(Target.values()).map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));

        assertEquals(
                declared,
                permitted,
                "Target and command_request's CHECK disagree. A target the database refuses is a"
                        + " constraint violation inside an adapter at the moment somebody types a"
                        + " command; one the database permits and the enum does not is a row"
                        + " nothing will ever claim.");
    }

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
