package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.settings.Database;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * Verifies that each run fixture closes safely after Docker-unavailable setup aborts and closes initialized pools.
 */
class RunFixtureCleanupTest {

    @Test
    void runnerTestClosesSafelyWhenDockerIsUnavailable() {
        final RunnerTest fixture = new RunnerTest();

        final TestAbortedException aborted =
                assertThrows(TestAbortedException.class, () -> fixture.open(RunFixtureCleanupTest::unavailableDocker));

        assertTrue(aborted.getMessage().contains("No Docker daemon reachable"), aborted.getMessage());
        assertNull(fixture.database());
        assertDoesNotThrow(fixture::close);
    }

    @Test
    void liveSwapTestClosesSafelyWhenDockerIsUnavailable() {
        final LiveSwapTest fixture = new LiveSwapTest();

        final TestAbortedException aborted =
                assertThrows(TestAbortedException.class, () -> fixture.open(RunFixtureCleanupTest::unavailableDocker));

        assertTrue(aborted.getMessage().contains("No Docker daemon reachable"), aborted.getMessage());
        assertNull(fixture.database());
        assertDoesNotThrow(fixture::close);
    }

    @Test
    void modeSwitchTestClosesSafelyWhenDockerIsUnavailable() {
        final ModeSwitchTest fixture = new ModeSwitchTest();

        final TestAbortedException aborted =
                assertThrows(TestAbortedException.class, () -> fixture.open(RunFixtureCleanupTest::unavailableDocker));

        assertTrue(aborted.getMessage().contains("No Docker daemon reachable"), aborted.getMessage());
        assertNull(fixture.database());
        assertDoesNotThrow(fixture::close);
    }

    @Test
    void runPlayersBackTestClosesSafelyWhenDockerIsUnavailable() {
        final RunPlayersBackTest fixture = new RunPlayersBackTest();

        final TestAbortedException aborted =
                assertThrows(TestAbortedException.class, () -> fixture.open(RunFixtureCleanupTest::unavailableDocker));

        assertTrue(aborted.getMessage().contains("No Docker daemon reachable"), aborted.getMessage());
        assertNull(fixture.database());
        assertDoesNotThrow(fixture::close);
    }

    @Test
    void runnerTestClosesInitializedDatabase() {
        final RunnerTest fixture = new RunnerTest();
        fixture.open();
        final Database opened = fixture.database();
        try {
            assertNotNull(opened);
            assertFalse(opened.dataSource().isClosed());
        } finally {
            fixture.close();
        }
        assertTrue(opened.dataSource().isClosed());
    }

    @Test
    void liveSwapTestClosesInitializedDatabase() {
        final LiveSwapTest fixture = new LiveSwapTest();
        fixture.open();
        final Database opened = fixture.database();
        try {
            assertNotNull(opened);
            assertFalse(opened.dataSource().isClosed());
        } finally {
            fixture.close();
        }
        assertTrue(opened.dataSource().isClosed());
    }

    @Test
    void modeSwitchTestClosesInitializedDatabase() {
        final ModeSwitchTest fixture = new ModeSwitchTest();
        fixture.open();
        final Database opened = fixture.database();
        try {
            assertNotNull(opened);
            assertFalse(opened.dataSource().isClosed());
        } finally {
            fixture.close();
        }
        assertTrue(opened.dataSource().isClosed());
    }

    @Test
    void runPlayersBackTestClosesInitializedDatabase() {
        final RunPlayersBackTest fixture = new RunPlayersBackTest();
        fixture.open();
        final Database opened = fixture.database();
        try {
            assertNotNull(opened);
            assertFalse(opened.dataSource().isClosed());
        } finally {
            fixture.close();
        }
        assertTrue(opened.dataSource().isClosed());
    }

    private static TestDatabase unavailableDocker() {
        assumeTrue(false, "No Docker daemon reachable");
        throw new AssertionError("unreachable");
    }
}
