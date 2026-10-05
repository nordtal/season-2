package eu.nordtal.season.stewardagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The agent refuses to recreate itself while the command line is assembled, before anything runs. */
class ComposeRefusesItselfTest {

    /** A service key: two spaces in, a name, a colon, nothing else on the line. */
    private static final Pattern SERVICE_KEY = Pattern.compile("  ([A-Za-z0-9][A-Za-z0-9._-]*):\\s*");

    private final Compose compose =
            new Compose(Path.of("/app/compose.yml"), Path.of("/does/not/exist/.env"), Path.of("/app"), "nordtal-s2");

    @Test
    void recreatingStewardAgentIsRefusedAndTheMessageSaysWhoDoesItInstead() {
        final IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> compose.recreate(Compose.SELF, line -> {}));

        assertTrue(refused.getMessage().contains("one-shot"), refused.getMessage());
    }

    @Test
    void aRunThatNamesItIsRefusedBeforeTheDaemonIsAsked() {
        final var docker = nowhere();
        final LocalStack stack = new LocalStack(
                docker,
                new eu.nordtal.season.stewardagent.docker.Containers(docker, "nordtal-s2"),
                new eu.nordtal.season.stewardagent.topology.ComposeTopology(
                        compose::definitions, "/backup-sources", java.time.Clock.systemUTC()),
                compose);

        for (final var result : List.of(stack.recreate(Compose.SELF), stack.deploy(Compose.SELF))) {
            assertFalse(result.triggered(), result.message());
            assertTrue(result.message().contains("renews it"), result.message());
        }
    }

    /** A daemon nobody answers on: a refused request must never get as far as asking it. */
    private static eu.nordtal.season.stewardagent.docker.Docker nowhere() {
        return new eu.nordtal.season.stewardagent.docker.Docker(new eu.nordtal.season.stewardagent.docker.DockerSocket(
                Path.of("/does/not/exist.sock"),
                java.time.Duration.ofSeconds(1),
                eu.nordtal.season.common.time.TestScheduler.SHARED));
    }

    @Test
    void andSoIsAnUpThatNamesItAmongOtherServices() {
        assertThrows(IllegalArgumentException.class, () -> compose.up(List.of("smp", Compose.SELF), line -> {}));
    }

    @Test
    void aDeploymentOfEverythingNamesEveryServiceAndLeavesThisOneOut() {
        final List<String> all = List.of("postgres", "smp", "steward", Compose.SELF);

        final List<String> deployed = StewardAgent.servicesToDeploy(all, List.of(), false);

        assertFalse(deployed.isEmpty(), "an empty list would mean every service, this one included");
        assertFalse(deployed.contains(Compose.SELF), deployed.toString());
        assertEquals(List.of("postgres", "smp", "steward"), deployed);
    }

    /** A request for the agent alone is refused, not turned into an empty list meaning every service. */
    @Test
    void aRequestForThisServiceAloneIsRefusedNotTurnedIntoEveryService() {
        final List<String> all = List.of("postgres", "smp", Compose.SELF);

        final IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class, () -> StewardAgent.servicesToDeploy(all, List.of(Compose.SELF), false));

        assertTrue(refused.getMessage().contains("one-shot"), refused.getMessage());
    }

    @Test
    void andSoIsARequestThatNamesItBesideAnotherService() {
        final List<String> all = List.of("postgres", "smp", Compose.SELF);

        assertThrows(
                IllegalArgumentException.class,
                () -> StewardAgent.servicesToDeploy(all, List.of("smp", Compose.SELF), false));
    }

    @Test
    void theBootstrapIsTheOneCallerThatMayCreateIt() {
        final List<String> all = List.of("postgres", Compose.SELF);

        assertEquals(all, StewardAgent.servicesToDeploy(all, List.of(), true));
    }

    @Test
    void theNameItRefusesIsAServiceComposeYmlReallyDeclares() throws IOException {
        // A renamed service key in compose.yml would refuse a service nobody deploys.
        final List<String> declared = serviceNames(repositoryRoot().resolve("compose.yml"));

        assertFalse(declared.isEmpty(), "no services parsed out of compose.yml");
        assertTrue(
                declared.contains(Compose.SELF), "compose.yml declares " + declared + ", none of them " + Compose.SELF);
    }

    /** Returns the top-level service keys, read as text so no daemon or environment is needed. */
    private static List<String> serviceNames(final Path composeFile) throws IOException {
        assertTrue(
                Files.isRegularFile(composeFile),
                composeFile + " no longer exists - if it moved,"
                        + " this path has to move with it, because a missing file is a check that silently"
                        + " stops running");
        final List<String> names = new ArrayList<>();
        boolean inServices = false;
        for (final String line : Files.readAllLines(composeFile, StandardCharsets.UTF_8)) {
            if (!line.startsWith(" ") && !line.isBlank() && !line.startsWith("#")) {
                inServices = line.startsWith("services:");
                continue;
            }
            final Matcher key = SERVICE_KEY.matcher(line);
            if (inServices && key.matches()) {
                names.add(key.group(1));
            }
        }
        return names;
    }

    /** Anchors on the directory holding settings.gradle.kts, never on the nearest file by name. */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        assertTrue(candidate != null, "no settings.gradle.kts above the working directory");
        return candidate;
    }
}
