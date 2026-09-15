package eu.nordtal.s2.steward.worker.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The truncation flag of the log search, without a docker daemon.
 *
 * <p>It is one boolean and it is worth a test of its own, because the value it used to carry was
 * wrong in exactly the case an admin hits most: a search that found everything there was and said
 * it had stopped early.</p>
 */
class LogSearchTest {

    private static final List<String> LOG = List.of(
            "[12:00:00 INFO]: Done (3.1s)!",
            "[12:00:01 WARN]: Can't keep up!",
            "[12:00:02 INFO]: Tillhfm joined the game",
            "[12:00:03 ERROR]: Exception in thread \"main\"",
            "[12:00:04 INFO]: Tillhfm left the game");

    @Test
    @DisplayName("exactly the limit is a complete answer, not a truncated one")
    void theLimitReachedIsNotTheLimitExceeded() {
        final WorkerApi.Search search = feed("tillhfm", 2);

        assertEquals(2, search.lines().size());
        assertFalse(search.truncated(),
                "both matches came back, so there is nothing for the reader to narrow down");
    }

    @Test
    @DisplayName("one match more than the limit is truncation, and says so")
    void oneMoreIsTruncation() {
        final WorkerApi.Search search = feed("tillhfm", 1);

        assertEquals(1, search.lines().size());
        assertTrue(search.truncated());
    }

    @Test
    @DisplayName("a term nobody logged is an empty answer that is not truncated")
    void nothingFoundIsNotTruncated() {
        final WorkerApi.Search search = feed("bunq", 1);

        assertTrue(search.lines().isEmpty());
        assertFalse(search.truncated());
    }

    @Test
    @DisplayName("the search is case-insensitive, in both directions")
    void caseIsNotPartOfTheSearch() {
        assertEquals(1, feed("EXCEPTION", 10).lines().size());
        assertEquals(2, feed("TiLLhFm", 10).lines().size());
    }

    private static WorkerApi.Search feed(final String pattern, final int limit) {
        final WorkerApi.Search search = new WorkerApi.Search(pattern, limit);
        LOG.forEach(search);
        return search;
    }
}
