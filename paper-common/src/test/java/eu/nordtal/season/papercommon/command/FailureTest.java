package eu.nordtal.season.papercommon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** A failed admin action names the database when one link of its cause chain comes from it. */
class FailureTest {

    @Test
    void aDatabaseErrorDeepInTheChainNamesTheDatabase() {
        final Throwable failure = new IllegalStateException("wrapped", new RuntimeException(new SQLException("gone")));
        assertEquals(Replies.Cause.DATABASE, Replies.Failure.of(failure).cause());
    }

    @Test
    void anyOtherErrorNamesNoCause() {
        assertEquals(
                Replies.Cause.OTHER,
                Replies.Failure.of(new IllegalArgumentException("bad")).cause());
    }
}
