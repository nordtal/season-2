package eu.nordtal.s2.common.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That every process which reports readiness reports it <em>late</em>.
 *
 * <h2>Why this is a text search and not a real test</h2>
 * For the same reason {@code FatalPathsStopTheServerTest} is one: the thing it protects cannot be
 * reached from a JVM with no server and no Discord gateway in it. {@link Readiness} itself is
 * covered properly by {@code ReadinessTest} - the arithmetic and the file handling are ordinary
 * code. What no unit test can reach is <b>where the call sits</b>, and that is the entire value of
 * the marker: a heartbeat started at the top of {@code onEnable} would prove that a JVM exists,
 * which is what the port already proved and is exactly the signal that reported a Paper server with
 * no season on it as healthy.
 *
 * <p>A grep is a weak test. It is also the only one available here, and the regression it catches -
 * somebody moving the call up while refactoring, or adding a refusal below it - is silent
 * everywhere else until a deployment looks fine and is not.</p>
 */
class ReadinessWiringTest {

    /** The three Paper plugins. Their refusals all go through a {@code severe("...")} call. */
    private static final List<String> PAPER_PLUGINS = List.of(
            "smp/src/main/java/eu/nordtal/s2/smp/SmpPlugin.java",
            "limbo/src/main/java/eu/nordtal/s2/limbo/LimboPlugin.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/HungerGamesPlugin.java");

    private static final String VELOCITY_PLUGIN =
            "network-control/src/main/templates/eu/nordtal/s2/networkcontrol/NetworkControlPlugin.java";

    private static final String BOT =
            "discord-bot/src/main/java/eu/nordtal/s2/discordbot/AccessBot.java";

    @Test
    @DisplayName("all five processes refresh the marker")
    void everyProcessBeats() throws IOException {
        for (final String relative : all()) {
            final String text = read(relative);
            assertTrue(text.contains("eu.nordtal.s2.common.health.Readiness"),
                    relative + " no longer imports Readiness, so its container has nothing to check"
                            + " and reports healthy from the moment the JVM starts");
            assertTrue(text.contains("Readiness.onDefaultPath("),
                    relative + " does not build a Readiness on the shared marker path; a path of its"
                            + " own would be one compose.yml does not look at");
            assertTrue(text.contains("::refresh"),
                    relative + " builds a Readiness and never refreshes it. A marker written once"
                            + " stays green for as long as the container's /tmp does, which is the"
                            + " half of this that a dead process would still pass");
        }
    }

    @Test
    @DisplayName("a Paper plugin starts its heartbeat below every refusal, and off the main thread")
    void thePaperPluginsBeatOnlyAfterAGoodStart() throws IOException {
        for (final String relative : PAPER_PLUGINS) {
            final String text = read(relative);

            final int lastRefusal = lastRefusal(text);
            final int heartbeat = text.indexOf("startHeartbeat();");
            assertTrue(lastRefusal >= 0, relative + " has no severe(\"...\") refusal any more, so"
                    + " this test is asserting nothing - check what replaced it");
            assertTrue(heartbeat >= 0, relative + " does not call startHeartbeat()");
            assertTrue(lastRefusal < heartbeat, relative + " starts its readiness heartbeat before"
                    + " its last refusal. A marker written above a refusal is a marker a plugin that"
                    + " refused to start still wrote, which is the exact state this signal exists to"
                    + " make visible.");

            assertTrue(text.contains("runTaskTimerAsynchronously(this, readiness::refresh"),
                    relative + " no longer beats on Bukkit's ASYNC scheduler. Two things break at"
                            + " once: a file write moves onto the main thread, and a server frozen"
                            + " mid-tick keeps beating from a thread the freeze does not touch.");
        }
    }

    @Test
    @DisplayName("the proxy does not beat on its fail-closed path")
    void theProxyBeatsOnlyWhenTheGateIsUp() throws IOException {
        // "The proxy is up but nobody can join" announces itself; "the proxy is up and the gate is
        // off" never did. A misconfigured network-control binds its port and answers pings while
        // refusing every login there is, so the port can say nothing about it and the marker is the
        // only thing that can.
        final String text = read(VELOCITY_PLUGIN);

        assertEquals(1, count(text, "startHeartbeat();"),
                VELOCITY_PLUGIN + " calls startHeartbeat() more than once; the fail-closed path is"
                        + " the one place it must not be called from");
        assertTrue(text.indexOf("startHeartbeat();") < text.indexOf("private void failClosed("),
                VELOCITY_PLUGIN + "'s only startHeartbeat() call is no longer inside start(...)");
        assertTrue(text.contains("heartbeat.cancel()"),
                VELOCITY_PLUGIN + " never cancels the beat, so a proxy on the way down keeps saying"
                        + " it is up for as long as its scheduler runs");
    }

    @Test
    @DisplayName("the bot beats only after Discord is ready and both reconciles are done")
    void theBotBeatsOnlyAfterItIsFullyUp() throws IOException {
        final String text = read(BOT);

        final int reconcile = text.lastIndexOf("roles.reconcile();");
        final int marker = text.indexOf("Readiness.onDefaultPath(");
        final int up = text.indexOf("started = true;");

        assertTrue(reconcile >= 0 && marker >= 0 && up >= 0,
                BOT + " no longer has the three landmarks this test reads");
        assertTrue(reconcile < marker, BOT + " builds its readiness marker before the startup"
                + " reconcile, so a bot that dies during it would still have reported ready");
        assertTrue(marker < up, BOT + "'s readiness marker is built after `started = true`, which"
                + " is the flag that decides whether the constructor cleaned up after itself");
        assertTrue(text.contains("timers.scheduleWithFixedDelay(guarded(\"readiness marker\""),
                BOT + " no longer beats on the existing timer executor. A pool of its own would keep"
                        + " reporting healthy while every scheduled duty this bot has was stuck.");
    }

    private static List<String> all() {
        final java.util.List<String> everything = new java.util.ArrayList<>(PAPER_PLUGINS);
        everything.add(VELOCITY_PLUGIN);
        everything.add(BOT);
        return List.copyOf(everything);
    }

    private static String read(final String relative) throws IOException {
        final Path source = repositoryRoot().resolve(relative);
        assertTrue(Files.isRegularFile(source), source + " is not where this test expects it");
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    /**
     * The last call to the plugin's own {@code severe(...)} refusal.
     *
     * <p>Whitespace before it, which is the whole subtlety: {@code smp} also calls
     * {@code getLogger().severe("...")} twice, far below {@code onEnable}, for a milestone track
     * that failed to reload - and that is a warning, not a refusal. A plain
     * {@code lastIndexOf("severe(\"")} finds one of those and this test passes on a plugin whose
     * heartbeat is in the wrong place.</p>
     */
    private static int lastRefusal(final String text) {
        int last = -1;
        for (int at = text.indexOf("severe(\""); at >= 0; at = text.indexOf("severe(\"", at + 1)) {
            if (at > 0 && Character.isWhitespace(text.charAt(at - 1))) {
                last = at;
            }
        }
        return last;
    }

    private static int count(final String text, final String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
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
