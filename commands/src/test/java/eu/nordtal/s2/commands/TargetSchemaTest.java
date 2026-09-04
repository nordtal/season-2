package eu.nordtal.s2.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Target} and the {@code CHECK} on {@code command_request.target} are one fact in two places.
 *
 * <h2>Why the pairing needs a test rather than care</h2>
 * The enum is in this module and the constraint is in {@code :common}'s migration, and neither is
 * visible from the other. A sixth process added here writes rows the database refuses - which is a
 * constraint violation inside whichever adapter submitted it, at the moment somebody typed a command
 * that has never worked. A constant removed here leaves rows nothing will ever claim.
 *
 * <p>The same pairing exists for {@code SeasonPhase} and {@code season_phase.phase}, established in
 * V4 for the same reason. This is that rule applied to the second enum the schema pins.</p>
 *
 * <h2>It reads the file off the classpath, not off a path</h2>
 * {@code :common}'s resources are on this module's runtime classpath, so the migration is reachable
 * as a resource and no {@code repositoryRootTestInputs} declaration is needed. That also means the
 * file this reads is the one that would actually be applied.
 */
class TargetSchemaTest {

    private static final String MIGRATION = "db/migration/V11__command_request.sql";

    private static String sql() throws IOException {
        try (InputStream stream = TargetSchemaTest.class.getClassLoader()
                .getResourceAsStream(MIGRATION)) {
            assertNotNull(stream, MIGRATION + " is not on the classpath - :common's resources are"
                    + " what put it there, so either the migration moved or the dependency did");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("every Target is permitted by the CHECK, and the CHECK permits nothing else")
    void theEnumAndTheConstraintAgree() throws IOException {
        final Matcher check = Pattern.compile(
                        "CHECK\\s*\\(target IN \\(([^)]*)\\)\\)", Pattern.CASE_INSENSITIVE)
                .matcher(sql());
        assertTrue(check.find(), "no CHECK on command_request.target in " + MIGRATION);

        final Set<String> permitted = Arrays.stream(check.group(1).split(","))
                .map(value -> value.trim().replace("'", ""))
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        final Set<String> declared = Arrays.stream(Target.values())
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertEquals(declared, permitted,
                "Target and command_request's CHECK disagree. A target the database refuses is a"
                        + " constraint violation inside an adapter at the moment somebody types a"
                        + " command; one the database permits and the enum does not is a row"
                        + " nothing will ever claim.");
    }

    @Test
    @DisplayName("every Target names a message key that both bundles carry")
    void everyTargetCanBeNamedToAPerson() throws IOException {
        // The sentence "no answer within 30 seconds - {target} is either down" is the one place a
        // process is named to somebody who is waiting for it, and it is produced on the surface
        // furthest from the logs.
        final String en = bundle("messages/commands/en.properties");
        final String de = bundle("messages/commands/de.properties");

        for (final Target target : Target.values()) {
            assertTrue(en.contains(target.messageKey() + "="),
                    target.messageKey() + " is not in the English bundle");
            assertTrue(de.contains(target.messageKey() + "="),
                    target.messageKey() + " is not in the German bundle");
        }
    }

    private static String bundle(final String path) throws IOException {
        try (InputStream stream = TargetSchemaTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertNotNull(stream, path + " is not on the classpath");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
