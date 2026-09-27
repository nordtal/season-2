package eu.nordtal.s2.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Holds the questions {@code init} asks against the installer's own table in {@code deploy/nordtal.sh}.
 *
 * A local setup asks the same things in the same words as a production install; a question renamed or
 * reworded there and not here is the drift this catches.
 */
class NordtalQuestionsTest {

    private static final String INSTALLER = read(Repository.root(Path.of("")).resolve("deploy/nordtal.sh"));

    @Test
    void everyBorrowedQuestionIsOneTheInstallerAsksInTheSameWords() {
        for (final LocalQuestions.Question question : LocalQuestions.ALL) {
            if (question.name().equals("STEWARD_UI_PUBLIC_URL")) {
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
