package eu.nordtal.s2.discordbot.hungergames;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The validations {@link Teams} can answer without ever reaching the database - the same reason
 * {@code TiersTest} and {@code LanguagesTest} stay in memory. Everything that actually reads or
 * writes {@code hg_game}/{@code hg_team}/{@code hg_member} needs a real PostgreSQL instance and is
 * therefore untested here; see the note at the end of this session's summary.
 * <p>
 * The {@link DataSource} handed to {@link Teams} throws on the first attempt to open a connection,
 * which is what proves these two checks run before any query - not just that they return the right
 * answer.
 * </p>
 */
class TeamsTest {

    private static final Teams TEAMS = new Teams(Jdbi.create(new DataSource() {
        // Reduced to the two methods JDBI's SqlObjectPlugin actually calls; every other method
        // throws to prove nothing here reaches past connection acquisition.
        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException("this test must never reach the database");
        }

        @Override
        public Connection getConnection(final String username, final String password) {
            throw new UnsupportedOperationException("this test must never reach the database");
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setLogWriter(final java.io.PrintWriter out) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setLoginTimeout(final int seconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getLoginTimeout() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T unwrap(final Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(final Class<?> iface) {
            throw new UnsupportedOperationException();
        }
    }).installPlugin(new SqlObjectPlugin()));

    @Test
    @DisplayName("a team name shorter than 3 characters is refused without touching the database")
    void tooShortNameIsRefused() {
        assertEquals(RegistrationResult.Status.INVALID_NAME, TEAMS.register("1", "ab").status());
    }

    @Test
    @DisplayName("a team name longer than 15 characters is refused without touching the database")
    void tooLongNameIsRefused() {
        assertEquals(RegistrationResult.Status.INVALID_NAME,
                TEAMS.register("1", "a".repeat(16)).status());
    }

    @Test
    @DisplayName("a name of exactly 3 or 15 characters passes the length check")
    void boundaryLengthsPassTheLengthCheck() {
        // Both of these reach the (stubbed, throwing) database next, which is itself the proof
        // that the length check accepted them: SQLException/UnsupportedOperationException, not
        // IllegalArgumentException, is what a caller sees past this point.
        assertThrows(3, () -> TEAMS.register("1", "abc"));
        assertThrows(15, () -> TEAMS.register("1", "a".repeat(15)));
    }

    @Test
    @DisplayName("inviting yourself is refused without touching the database")
    void invitingYourselfIsRefused() {
        assertEquals(InviteResult.Status.CANNOT_INVITE_SELF,
                TEAMS.invite("1", "1").status());
    }

    private static void assertThrows(final int nameLength, final Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected the stub database to be reached for a " + nameLength
                    + "-character name");
        } catch (final RuntimeException expected) {
            // UnsupportedOperationException from the stub DataSource, possibly wrapped by JDBI.
        }
    }
}
