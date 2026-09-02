package eu.nordtal.s2.common;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules {@code deploy/minecraft/entrypoint.sh} was taught the hard way, kept from being untaught.
 *
 * <h2>Why a text test for a shell script</h2>
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
     * <p>It has to be, and the first version of this test proved why by failing on all four rules:
     * the file explains each of them at length, so a search for {@code /proc/1/fd/1} finds the
     * paragraph forbidding it, and a search for {@code remain-on-exit} finds the paragraph saying
     * where it has to go. What is being asserted here is what the script <em>does</em>.</p>
     */
    private static String script;

    @BeforeAll
    static void read() throws IOException {
        final String raw = Files.readString(
                repositoryRoot().resolve("deploy/minecraft/entrypoint.sh"), StandardCharsets.UTF_8);
        script = raw.lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    @Test
    @DisplayName("the console is never mirrored to /proc/1/fd/1")
    void neverPipesIntoTheContainersStdout() {
        // deploy/README.md#never-mirror-the-console-with-tmux-pipe-pane. Measured 2026-09-01 on
        // Docker 29.4.1: a pipe-pane writer holding a second handle on the container's stdout pipe
        // wedges it so completely that SIGTERM never reaches PID 1, the shutdown trap never runs,
        // and the container survives SIGKILL - `docker rm -f` fails with "did not receive an exit
        // event", and only a daemon restart clears it. It is the obvious way to do this, which is
        // exactly why it needs a test and not only a paragraph.
        assertFalse(script.contains("/proc/1/fd/1"),
                "entrypoint.sh writes to /proc/1/fd/1. That wedges the container beyond recovery.");
    }

    @Test
    @DisplayName("remain-on-exit is set before the session exists, not after")
    void theExitStatusSurvivesAServerThatDiesAtOnce() {
        // Set afterwards, it works for every server that runs for a while and fails for the only
        // one where the status matters: a JVM that dies at once takes the session with it before
        // the option lands, every display-message below falls back to `|| echo 1`, and a real exit
        // status of 3 is reported as 1. Verified in a container 2026-09-02, both ways round.
        final int option = script.indexOf("remain-on-exit on");
        final int session = script.indexOf("new-session");
        assertTrue(option >= 0, "entrypoint.sh no longer keeps the pane after the JVM exits, so the"
                + " server's exit status cannot be read back at all");
        assertTrue(option < session,
                "remain-on-exit is set after new-session. A server that dies immediately is gone"
                        + " before it applies, and its exit status is reported as 1 whatever it was.");
        assertTrue(script.contains("exit-empty off"),
                "without `exit-empty off` there is no tmux server to set a global option on before"
                        + " the first session exists");
    }

    @Test
    @DisplayName("the boot capture is attached in the same invocation that starts the server")
    void theCaptureCannotMissAPaneThatDiesInstantly() {
        // A separate pipe-pane call against a pane that has already exited fails with "target pane
        // has exited", and the output that killed it is gone. Measured in a container 2026-09-02.
        final int session = script.indexOf("new-session");
        final int pipe = script.indexOf("pipe-pane -o");
        assertTrue(pipe > session,
                "the boot capture is no longer attached with the session");
        assertFalse(script.substring(session, pipe).contains("\ntmux "),
                "pipe-pane is a separate tmux invocation again. Against a pane that died on startup"
                        + " it fails with \"target pane has exited\" and the crash output is lost -"
                        + " which is the whole failure this capture exists for.");
    }

    @Test
    @DisplayName("a server that dies before Paper logs still gets its output into the container log")
    void thePostMortemIsStillThere() {
        // `tail -F latest.log` can show nothing before that file exists, so a Paperclip that cannot
        // load the server jar printed forty lines into a tmux pane and the container log said
        // "server exited with status 1" and nothing else, forever, in a restart loop.
        assertTrue(script.contains("cat \"$BOOT_LOG\" >&2"),
                "entrypoint.sh no longer prints the boot capture when the server died before"
                        + " creating latest.log. That is the case where it is the only copy.");
    }

    @Test
    @DisplayName("the seeded velocity.toml says who this network is and how many it takes")
    void theProxyDoesNotAdvertiseItselfAsAVelocityServer() {
        // Seeded velocity.toml carried only what a login needs, so the server browser showed
        // Velocity's own default - "A Velocity Server" in Velocity blue - and 500 slots that no
        // backend honoured. On launch day, in every player's list.
        assertTrue(script.contains("motd = "),
                "the seeded velocity.toml no longer sets a MOTD, so the browser falls back to"
                        + " \"A Velocity Server\"");
        assertTrue(script.contains("show-max-players = "),
                "the seeded velocity.toml no longer sets show-max-players");
        assertTrue(script.contains("${MAX_PLAYERS:-500}"),
                "the browser's player count no longer comes from the same variable as the backends'"
                        + " real limit, so the two can advertise different numbers again");
    }

    @Test
    @DisplayName("the player limit is enforced on every start, not seeded once")
    void thePlayerLimitCannotDriftFromTheProxys() {
        assertTrue(script.contains("set_property \"$DATA/server.properties\" max-players"),
                "nothing writes max-players any more, so every backend keeps Paper's default of 20"
                        + " while the proxy advertises 500");
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
        throw new IllegalStateException("no settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
