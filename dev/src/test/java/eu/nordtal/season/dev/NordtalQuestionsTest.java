package eu.nordtal.season.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Holds the questions {@code init} asks against the installer's own table in {@code deploy/nordtal.sh}.
 */
class NordtalQuestionsTest {

    private static final String INSTALLER = read(Repository.root(Path.of("")).resolve("deploy/nordtal.sh"));

    @Test
    void everyBorrowedQuestionIsOneTheInstallerAsksInTheSameWords() {
        for (final LocalQuestions.Question question : LocalQuestions.ALL) {
            if (question.name().equals("STEWARD_PUBLIC_URL")) {
                continue;
            }
            assertTrue(
                    Pattern.compile("(?ms)^QUESTIONS=\\(.*?^\\s+" + question.name() + "$.*?^\\)")
                            .matcher(INSTALLER)
                            .find(),
                    question.name() + " is asked locally and is in no table of deploy/nordtal.sh");
            assertEquals(entry("QUESTION_PROMPT", question.name()), question.prompt(), question.name());
        }
    }

    @Test
    void theLicenceIsExplainedInTheInstallersWordsToo() {
        assertEquals(
                entry("QUESTION_HINT", "EULA"), LocalQuestions.ALL.getFirst().hint());
    }

    @Test
    void whatTheInstallerChecksAsADiscordIdIsCheckedHereToo() {
        for (final LocalQuestions.Question question : LocalQuestions.ALL) {
            if (INSTALLER.contains("[" + question.name() + "]=looks_like_snowflake")) {
                assertTrue(question.check().test("123456789012345678"), question.name());
                assertFalse(question.check().test("<@123456789012345678>"), question.name());
            }
        }
    }

    @Test
    void aSecretOnlyOneServiceReadsIsWrittenWhereTheInstallerWritesIt() {
        final Matcher table =
                Pattern.compile("(?ms)^SERVICE_SECRETS=\\((.*?)^\\)").matcher(INSTALLER);
        assertTrue(table.find(), "SERVICE_SECRETS is gone from deploy/nordtal.sh");
        for (final LocalQuestions.Question question : LocalQuestions.ALL) {
            final Matcher row = Pattern.compile("(?m)^\\s+\"" + question.name() + " ([a-z-]+)(?: ([A-Z0-9_]+))?\"$")
                    .matcher(table.group(1));
            final LocalQuestions.Home home = question.home();
            if (!row.find()) {
                assertNull(home, question.name() + " is the shared file's, as the installer keeps it");
                continue;
            }
            final String name = row.group(2) == null ? question.name() : row.group(2);
            assertEquals(
                    new LocalQuestions.Home(row.group(1), name),
                    home,
                    question.name() + " is not written where deploy/nordtal.sh writes it");
        }
        final Matcher services =
                Pattern.compile("(?m)^\\s+\"[A-Z0-9_]+ ([a-z-]+)").matcher(table.group(1));
        final Set<String> written = new LinkedHashSet<>();
        while (services.find()) {
            written.add(services.group(1));
        }
        assertEquals(
                written,
                new LinkedHashSet<>(Setup.SECRET_SERVICES),
                "init makes a secrets directory for other services than the installer writes a file for");
    }

    private static String entry(final String table, final String name) {
        final Matcher block =
                Pattern.compile("(?ms)^declare -A " + table + "=\\((.*?)^\\)").matcher(INSTALLER);
        assertTrue(block.find(), table + " is gone from deploy/nordtal.sh");
        final Matcher entry =
                Pattern.compile("(?ms)^\\s+\\[" + name + "\\]=\"(.*?)\"$").matcher(block.group(1));
        assertTrue(entry.find(), name + " has no entry in " + table);
        return entry.group(1).replaceAll("\\s*\\n\\s*", " ");
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
