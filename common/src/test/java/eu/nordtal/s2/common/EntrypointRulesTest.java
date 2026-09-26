package eu.nordtal.s2.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The rules {@code deploy/minecraft/entrypoint.sh} was taught the hard way, kept from being untaught.
 *
 * <b>Why a text test for a shell script</b>
 *
 * Because there is no other kind available here and the alternative is nothing. Every rule below was
 * established by running a container and watching it fail, each is a single line that looks
 * removable, and each has a failure mode that is invisible from outside: a container that reports
 * the wrong exit status, or one whose crash cause reaches no log at all, or one that cannot be
 * killed. The container drills that produced them are in {@code deploy/README.md}; this is what
 * notices when a line goes missing between them.
 */
class EntrypointRulesTest {

    /**
     * The script with every full-line comment removed.
     *
     * It has to be, and the first version of this test proved why by failing on all four rules:
     * the file explains each of them at length, so a search for {@code /proc/1/fd/1} finds the
     * paragraph forbidding it, and a search for {@code remain-on-exit} finds the paragraph saying
     * where it has to go. What is being asserted here is what the script <em>does</em>.
     */
    private static String script;

    @BeforeAll
    static void read() throws IOException {
        final String raw =
                Files.readString(repositoryRoot().resolve("deploy/minecraft/entrypoint.sh"), StandardCharsets.UTF_8);
        script = raw.lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    @Test
    void theConsoleIsNeverMirroredToProc1Fd1() {
        // A pipe-pane writer wedges the container's stdout so SIGTERM never reaches PID 1; see deploy/README.md.
        assertFalse(
                script.contains("/proc/1/fd/1"),
                "entrypoint.sh writes to /proc/1/fd/1. That wedges the container beyond recovery.");
    }

    @Test
    void remainOnExitIsSetBeforeTheSessionExistsNotAfter() {
        // Set after new-session, a JVM that dies at once loses its exit status before the option lands.
        final int option = script.indexOf("remain-on-exit on");
        final int session = script.indexOf("new-session");
        assertTrue(
                option >= 0,
                "entrypoint.sh does not keep the pane after the JVM exits, so the"
                        + " server's exit status cannot be read back at all");
        assertTrue(
                option < session,
                "remain-on-exit is set after new-session. A server that dies immediately is gone"
                        + " before it applies, and its exit status is reported as 1 whatever it was.");
        assertTrue(
                script.contains("exit-empty off"),
                "without `exit-empty off` there is no tmux server to set a global option on before"
                        + " the first session exists");
    }

    @Test
    void theBootCaptureIsAttachedInTheSameInvocationThatStartsTheServer() {
        // A pipe-pane call against an exited pane fails, and the output that killed it is gone.
        final int session = script.indexOf("new-session");
        final int pipe = script.indexOf("pipe-pane -o");
        assertTrue(pipe > session, "the boot capture is not attached with the session");
        assertFalse(
                script.substring(session, pipe).contains("\ntmux "),
                "pipe-pane is a separate tmux invocation again. Against a pane that died on startup"
                        + " it fails with \"target pane has exited\" and the crash output is lost -"
                        + " which is the whole failure this capture exists for.");
    }

    @Test
    void aServerThatDiesBeforePaperLogsStillGetsItsOutputIntoTheContainerLog() {
        // latest.log may never exist, so the pane output is the only record of a failed start.
        assertTrue(
                script.contains("cat \"$BOOT_LOG\" >&2"),
                "entrypoint.sh does not print the boot capture when the server died before"
                        + " creating latest.log. That is the case where it is the only copy.");
    }

    @Test
    void theSeededVelocityTomlDoesNotCarryAMotdOrAPlayerCount() {
        // The MOTD and player count belong to proxy's network.yml; a copy here would go stale.
        assertFalse(
                script.contains("printf 'motd = "),
                "the entrypoint seeds a MOTD into velocity.toml again. That file is written once and"
                        + " never touched, so the copy in it goes stale the first time network.yml"
                        + " changes - and nothing says so.");
        assertFalse(
                script.contains("show-max-players"),
                "the entrypoint seeds show-max-players again, which is the plugin's answer to"
                        + " ProxyPingEvent and must have exactly one source");
        assertFalse(
                script.contains("VELOCITY_MOTD"),
                "VELOCITY_MOTD names nothing Velocity reads - the MOTD is"
                        + " NETWORK_MOTD_<PHASE>, mapped onto network.yml in compose.yml.");
    }

    @Test
    void theBackendsAreGivenTheNetworksOwnLimitOutOfOneVariable() {
        assertTrue(
                script.contains("set_property \"$DATA/server.properties\" max-players"),
                "nothing writes max-players, so every backend keeps Paper's default of 20"
                        + " and the 21st player is refused after the login gate and the pack");
        assertTrue(
                script.contains("${MAX_PLAYERS:-}"),
                "the backends' max-players does not come from MAX_PLAYERS, which compose fills"
                        + " from the same NETWORK_MAX_PLAYERS the proxy is given");
        // The variable reference, not the name: a comment in the script may still name it.
        assertFalse(
                script.contains("${BACKEND_MAX_PLAYERS"),
                "the entrypoint reads BACKEND_MAX_PLAYERS again. That was a SECOND player number,"
                        + " deliberately out of reach - and it was the one every screen on a backend"
                        + " could actually reach, so the browser advertised 500 while the tab list"
                        + " said 3/1000. Retired 2026-09-04; there is one number now.");
    }

    /** The directory holding {@code settings.gradle.kts}, not the nearest file by name. */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
