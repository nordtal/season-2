package eu.nordtal.s2.steward.deployer;

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

/**
 * The deployer must never recreate itself, and the refusal has to happen before anything runs.
 *
 * These tests need no docker daemon on purpose: the guard is checked while the command line is
 * being assembled, which is the only point at which refusing still helps. A guard that fired after
 * {@code docker compose up} had started would be a container recreating the process that asked.
 */
class ComposeRefusesItselfTest {

    /** A service key: two spaces in, a name, a colon, nothing else on the line. */
    private static final Pattern SERVICE_KEY = Pattern.compile("  ([A-Za-z0-9][A-Za-z0-9._-]*):\\s*");

    private final Compose compose =
            new Compose(Path.of("/app/compose.yml"), Path.of("/does/not/exist/.env"), Path.of("/app"), "nordtal-s2");

    @Test
    void recreatingStewardDeployerIsRefusedAndTheMessageSaysWhoDoesItInstead() {
        final IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> compose.recreate(Compose.SELF, line -> {}));

        assertTrue(refused.getMessage().contains("setup script"), refused.getMessage());
    }

    @Test
    void andSoIsAnUpThatNamesItAmongOtherServices() {
        assertThrows(IllegalArgumentException.class, () -> compose.up(List.of("smp", Compose.SELF), line -> {}));
    }

    @Test
    void aDeploymentOfEverythingNamesEveryServiceAndLeavesThisOneOut() {
        final List<String> all = List.of("postgres", "smp", "steward-ui", Compose.SELF);

        final List<String> deployed = StewardDeployer.servicesToDeploy(all, List.of(), false);

        // An empty list means EVERY service to `docker compose up`, this one included.
        assertFalse(deployed.isEmpty(), "an empty list would mean every service, this one included");
        assertFalse(deployed.contains(Compose.SELF), deployed.toString());
        assertEquals(List.of("postgres", "smp", "steward-ui"), deployed);
    }

    /**
     * The removal is for the whole-stack path and for nothing else.
     *
     * `servicesToDeploy` took SELF out of any request, including one that consisted of nothing
     * but SELF - which left the empty list, and an empty list means EVERY service to
     * {@code docker compose up}. So asking for the deployer alone deployed the entire project, the
     * deployer included, and the new container killed the process still writing the report. It is
     * the same bug the whole-stack path was fixed for, arrived at from the other side, and it
     * walked straight past {@link Compose#up}'s own refusal because the name had been removed one
     * step before it got there.
     */
    @Test
    void aRequestForThisServiceAloneIsRefusedNotTurnedIntoEveryService() {
        final List<String> all = List.of("postgres", "smp", Compose.SELF);

        final IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> StewardDeployer.servicesToDeploy(all, List.of(Compose.SELF), false));

        // The refusal has to NAME `nordtal.sh` as the thing that does the job instead.
        assertTrue(refused.getMessage().contains("nordtal.sh"), refused.getMessage());
    }

    @Test
    void andSoIsARequestThatNamesItBesideAnotherService() {
        final List<String> all = List.of("postgres", "smp", Compose.SELF);

        assertThrows(
                IllegalArgumentException.class,
                () -> StewardDeployer.servicesToDeploy(all, List.of("smp", Compose.SELF), false));
    }

    @Test
    void theBootstrapIsTheOneCallerThatMayCreateIt() {
        final List<String> all = List.of("postgres", Compose.SELF);

        assertEquals(all, StewardDeployer.servicesToDeploy(all, List.of(), true));
    }

    @Test
    void theNameItRefusesIsAServiceComposeYmlReallyDeclares() throws IOException {
        // A renamed service key in compose.yml would refuse a service nobody deploys instead.
        final List<String> declared = serviceNames(repositoryRoot().resolve("compose.yml"));

        assertFalse(declared.isEmpty(), "no services parsed out of compose.yml");
        assertTrue(
                declared.contains(Compose.SELF), "compose.yml declares " + declared + ", none of them " + Compose.SELF);
    }

    /**
     * The top-level service keys, read as text.
     *
     * Not {@link Compose#services()}, which is {@code docker compose config} and therefore a
     * daemon and a full set of {@code ${X:?}} values - neither of which these tests have, by
     * design. The keys are two spaces in under {@code services:} and that is all this needs.
     */
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
