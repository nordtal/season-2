package eu.nordtal.season.stewardagent.docker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.season.common.time.TestScheduler;
import eu.nordtal.season.internalapi.agent.RedeployResult;
import eu.nordtal.season.stewardagent.TestProject;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * A stop against a real daemon waits the grace compose gave the service, and no fixed one of its own.
 *
 * It needs a scratch project named by {@code NORDTAL_TEST_PROJECT} with a service {@code slow} whose
 * {@code stop_grace_period} is a few seconds and whose process ignores SIGTERM; it skips itself otherwise.
 */
class StopGraceIntegrationTest {

    @Test
    void aStopWaitsTheServicesOwnGraceAndThenSaysItKilled() {
        final String project = TestProject.acting().orElse(null);
        assumeTrue(project != null, "no scratch project named - skipping");
        final DockerSocket socket = new DockerSocket(TestScheduler.SHARED);
        assumeTrue(socket.isReachable(), "no docker socket - skipping");
        final Docker docker = new Docker(socket);
        final Docker.Container slow = docker.containers(project).stream()
                .filter(container -> "slow".equals(container.service()))
                .findFirst()
                .orElse(null);
        assumeTrue(slow != null, "the scratch project has no service slow - skipping");
        final int grace = docker.inspect(slow.id()).stopTimeout();
        assertTrue(
                grace > 0 && grace < 30, "the scratch service's stop_grace_period should be a few seconds: " + grace);

        final Instant before = Instant.now();
        final RedeployResult result = new Containers(docker, project).stop(slow.id());
        final Duration took = Duration.between(before, Instant.now());

        assertFalse(result.triggered(), result.message());
        assertTrue(result.message().contains("within " + grace + " seconds"), result.message());
        assertTrue(took.toSeconds() >= grace, "the kill came before the grace was over: " + took);
        assertTrue(took.toSeconds() < grace + 10, "the stop took far longer than its grace: " + took);
    }
}
