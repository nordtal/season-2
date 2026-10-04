package eu.nordtal.s2.discordbot.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.registration.Game;
import java.sql.Connection;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.Test;

/** The {@link Teams} validations that run before any query, against a database that throws on connect. */
class TeamsTest {

    private static final Teams TEAMS = new Teams(
            Jdbi.create(new DataSource() {
                        // Only the two methods JDBI's SqlObjectPlugin calls; every other throws.
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
                    })
                    .installPlugin(new SqlObjectPlugin()),
            Game.HUNGER_GAMES);

    @Test
    void aTeamNameShorterThan3CharactersIsRefusedWithoutTouchingTheDatabase() {
        assertEquals(
                RegistrationResult.Status.INVALID_NAME,
                TEAMS.register(DiscordId.of("1"), "ab").status());
    }

    @Test
    void aTeamNameLongerThan15CharactersIsRefusedWithoutTouchingTheDatabase() {
        assertEquals(
                RegistrationResult.Status.INVALID_NAME,
                TEAMS.register(DiscordId.of("1"), "a".repeat(16)).status());
    }

    @Test
    void aNameOfExactly3Or15CharactersPassesTheLengthCheck() {
        // Both reach the throwing database next, so the length check accepted them.
        assertThrows(3, () -> TEAMS.register(DiscordId.of("1"), "abc"));
        assertThrows(15, () -> TEAMS.register(DiscordId.of("1"), "a".repeat(15)));
    }

    @Test
    void invitingYourselfIsRefusedWithoutTouchingTheDatabase() {
        assertEquals(
                InviteResult.Status.CANNOT_INVITE_SELF, TEAMS.invite("1", "1").status());
    }

    private static void assertThrows(final int nameLength, final Runnable action) {
        try {
            action.run();
            throw new AssertionError(
                    "expected the stub database to be reached for a " + nameLength + "-character name");
        } catch (final RuntimeException expected) {
            // The stub DataSource's UnsupportedOperationException, possibly wrapped by JDBI.
        }
    }
}
