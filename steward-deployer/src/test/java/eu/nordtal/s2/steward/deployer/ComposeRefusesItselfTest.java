package eu.nordtal.s2.steward.deployer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deployer must never recreate itself, and the refusal has to happen before anything runs.
 *
 * <p>These tests need no docker daemon on purpose: the guard is checked while the command line is
 * being assembled, which is the only point at which refusing still helps. A guard that fired after
 * {@code docker compose up} had started would be a container recreating the process that asked.</p>
 */
class ComposeRefusesItselfTest {

    /** A service key: two spaces in, a name, a colon, nothing else on the line. */
    private static final Pattern SERVICE_KEY = Pattern.compile("  ([A-Za-z0-9][A-Za-z0-9._-]*):\\s*");

    private final Compose compose = new Compose(
            Path.of("/app/compose.yml"), Path.of("/does/not/exist/.env"), Path.of("/app"), "nordtal-s2");

    @Test
    @DisplayName("recreating steward-deployer is refused, and the message says who does it instead")
    void refusesToRecreateItself() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> compose.recreate(Compose.SELF, line -> { }));

        assertTrue(refused.getMessage().contains("setup script"), refused.getMessage());
    }

    @Test
    @DisplayName("and so is an `up` that names it among other services")
    void refusesToBringItselfUpByName() {
        assertThrows(IllegalArgumentException.class,
                () -> compose.up(List.of("smp", Compose.SELF), line -> { }));
    }

    @Test
    @DisplayName("a deployment of everything names every service, and leaves this one out")
    void theWholeStackIsNamedServiceByService() {
        final List<String> all = List.of("postgres", "smp", "steward-ui", Compose.SELF);

        final List<String> deployed = StewardDeployer.servicesToDeploy(all, List.of(), false);

        // The bug this holds: an empty list means EVERY service to `docker compose up`, so handing
        // one over on the whole-stack path undid the removal three lines above it. The refusal only
        // ever fired for a request that named the deployer by hand - which is the rare case.
        assertFalse(deployed.isEmpty(), "an empty list would mean every service, this one included");
        assertFalse(deployed.contains(Compose.SELF), deployed.toString());
        assertEquals(List.of("postgres", "smp", "steward-ui"), deployed);
    }

    /**
     * The removal is for the whole-stack path and for nothing else.
     *
     * <p>`servicesToDeploy` took SELF out of any request, including one that consisted of nothing
     * but SELF - which left the empty list, and an empty list means EVERY service to
     * {@code docker compose up}. So asking for the deployer alone deployed the entire project, the
     * deployer included, and the new container killed the process still writing the report. It is
     * the same bug the whole-stack path was fixed for, arrived at from the other side, and it
     * walked straight past {@link Compose#up}'s own refusal because the name had been removed one
     * step before it got there.</p>
     */
    @Test
    @DisplayName("a request for this service alone is refused, not turned into every service")
    void namingOnlyItselfIsRefused() {
        final List<String> all = List.of("postgres", "smp", Compose.SELF);

        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> StewardDeployer.servicesToDeploy(all, List.of(Compose.SELF), false));

        // `nordtal.sh` and not `setup` since season-2-ops/124 renamed the script. What this
        // assertion is for is that the refusal NAMES the thing that does the job instead - a
        // refusal that only says no leaves somebody with a container to renew and no way to do it.
        assertTrue(refused.getMessage().contains("nordtal.sh"), refused.getMessage());
    }

    @Test
    @DisplayName("and so is a request that names it beside another service")
    void namingItBesideAnotherIsRefused() {
        final List<String> all = List.of("postgres", "smp", Compose.SELF);

        assertThrows(IllegalArgumentException.class,
                () -> StewardDeployer.servicesToDeploy(all, List.of("smp", Compose.SELF), false));
    }

    @Test
    @DisplayName("the bootstrap is the one caller that may create it")
    void theBootstrapMayCreateIt() {
        final List<String> all = List.of("postgres", Compose.SELF);

        assertEquals(all, StewardDeployer.servicesToDeploy(all, List.of(), true));
    }

    @Test
    @DisplayName("the name it refuses is a service compose.yml really declares")
    void theNameIsAServiceInTheComposeFile() throws IOException {
        // Asserting Compose.SELF.equals("steward-deployer") was two copies of one string agreeing
        // with each other, which they always will. The drift that matters is against compose.yml:
        // rename the service key there and the guard silently stops guarding - it refuses a service
        // nobody deploys and lets the real one through.
        final List<String> declared = serviceNames(repositoryRoot().resolve("compose.yml"));

        assertFalse(declared.isEmpty(), "no services parsed out of compose.yml");
        assertTrue(declared.contains(Compose.SELF),
                "compose.yml declares " + declared + ", none of them " + Compose.SELF);
    }

    /**
     * The top-level service keys, read as text.
     *
     * <p>Not {@link Compose#services()}, which is {@code docker compose config} and therefore a
     * daemon and a full set of {@code ${X:?}} values - neither of which these tests have, by
     * design. The keys are two spaces in under {@code services:} and that is all this needs.</p>
     */
    private static List<String> serviceNames(Path composeFile) throws IOException {
        assertTrue(Files.isRegularFile(composeFile), composeFile + " no longer exists - if it moved,"
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
