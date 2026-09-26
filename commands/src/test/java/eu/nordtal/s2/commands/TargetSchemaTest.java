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
 * The pairing needs a test rather than care, because the enum is in this module and the
 * constraint is in {@code :common}'s migration, and neither is visible from the other. A sixth
 * process added here writes rows the database refuses - a constraint violation inside whichever
 * adapter submitted it, at the moment somebody typed a command that has never worked. A constant
 * removed here leaves rows nothing will ever claim.
 *
 * The same pairing exists for {@code SeasonPhase} and {@code season_phase.phase}, for the same
 * reason. This is that rule applied to the second enum the schema pins.
 *
 * It reads the migration file off the classpath, not off a path: {@code :common}'s resources are
 * on this module's runtime classpath, so no {@code repositoryRootTestInputs} declaration is
 * needed, and the file this reads is the one that would actually be applied.
 */
class TargetSchemaTest {

    private static final String MIGRATION = "db/migration/V11__command_request.sql";

    private static String sql() throws IOException {
        try (InputStream stream = TargetSchemaTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertNotNull(
                    stream,
                    MIGRATION + " is not on the classpath - :common's resources are"
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

        final Set<String> declared = Arrays.stream(Target.values())
                .map(Enum::name)
                .filter(name -> !Target.LOCAL.name().equals(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertEquals(
                declared,
                permitted,
                "Target and command_request's CHECK disagree. A target the database refuses is a"
                        + " constraint violation inside an adapter at the moment somebody types a"
                        + " command; one the database permits and the enum does not is a row"
                        + " nothing will ever claim.");
    }

    @Test
    void localIsDeliberatelyNotPermittedByTheCheck() throws IOException {
        // The one target that is not an address, and the constraint is where that is enforced rather than merely.
        assertTrue(
                !sql().contains("'LOCAL'"),
                "command_request's CHECK permits LOCAL. It must not: a LOCAL command never"
                        + " travels, so such a row can only be a mistake, and the database is the"
                        + " last place that can still say so.");
    }

    @Test
    void everyTargetNamesAMessageKeyThatBothBundlesCarry() throws IOException {
        // The sentence "no answer within 30 seconds - {target} is either down" is where a process gets named.
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
