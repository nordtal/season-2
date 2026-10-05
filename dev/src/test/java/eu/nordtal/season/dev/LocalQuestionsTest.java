package eu.nordtal.season.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LocalQuestionsTest {

    @Test
    void aSchemeAndAHostWithOrWithoutAPortIsAnAddress() {
        for (final String good :
                List.of("http://localhost:5173", "http://steward.localhost:8080", "https://steward.dev.nordtal.eu")) {
            assertTrue(LocalQuestions.looksLikeBrowserUrl(good), good);
        }
    }

    @Test
    void aPathAQueryATrailingSlashOrNoSchemeIsRefused() {
        for (final String wrong : List.of(
                "",
                " ",
                "localhost:5173",
                "http://",
                "https://host/",
                "https://host/auth",
                "http://host?x=1",
                "ftp://host",
                "http://host:80 ",
                "http://under_score")) {
            assertFalse(LocalQuestions.looksLikeBrowserUrl(wrong), "'" + wrong + "' was accepted as an address");
        }
    }

    @Test
    void theRelyingPartyIdIsTheHostAndNothingElse() {
        assertEquals("localhost", LocalQuestions.hostOf("http://localhost:5173"));
        assertEquals("steward.dev.nordtal.eu", LocalQuestions.hostOf("https://steward.dev.nordtal.eu"));
    }

    @Test
    void discordIsSkippableHereAndTheLicenceAndTheAddressAreNot() {
        for (final LocalQuestions.Question question : LocalQuestions.ALL) {
            final boolean discord =
                    question.name().startsWith("NORDTAL_") || question.name().startsWith("STEWARD_DISCORD_");
            if (discord) {
                assertTrue(
                        question.kind() == LocalQuestions.Kind.OPTIONAL_PLAIN
                                || question.kind() == LocalQuestions.Kind.OPTIONAL_SECRET,
                        question.name() + " cannot be skipped, so a checkout without a test guild cannot start");
            }
        }
        assertEquals(LocalQuestions.Kind.LICENCE, LocalQuestions.ALL.getFirst().kind());
        assertEquals(LocalQuestions.Kind.PLAIN, LocalQuestions.ALL.get(1).kind());
    }

    @Test
    void onlyAYesAcceptsTheLicence() {
        for (final String yes : List.of("y", "Y", "yes", "YES", "true", " yes ")) {
            assertTrue(LocalQuestions.isYes(yes), yes);
        }
        for (final String no : List.of("", "n", "no", "ja", "sure", "yess")) {
            assertFalse(LocalQuestions.isYes(no), no);
        }
    }
}
