package eu.nordtal.s2.common.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checks that every process which reports readiness reports it last, after every refusal.
 *
 * A text search, since where the call sits cannot be reached from a JVM without a server.
 */
class ReadinessWiringTest {

    /** The three Paper plugins, whose refusals all go through a {@code severe("...")} call. */
    private static final List<String> PAPER_PLUGINS =
            List.of("paper-common/src/main/java/eu/nordtal/s2/papercommon/plugin/NordtalPlugin.java");

    private static final String VELOCITY_PLUGIN = "proxy/src/main/templates/eu/nordtal/s2/proxy/ProxyPlugin.java";

    private static final String BOT = "discord-bot/src/main/java/eu/nordtal/s2/discordbot/AccessBot.java";

    @Test
    void everyKindOfProcessRefreshesTheMarker() throws IOException {
        for (final String relative : all()) {
            final String text = read(relative);
            assertTrue(
                    text.contains("eu.nordtal.s2.common.health.Readiness"),
                    relative + " does not import Readiness, so its container has nothing to check"
                            + " and reports healthy from the moment the JVM starts");
            assertTrue(
                    text.contains("Readiness.onDefaultPath("),
                    relative + " does not build a Readiness on the shared marker path; a path of its"
                            + " own would be one compose.yml does not look at");
            assertTrue(
                    text.contains("::refresh"),
                    relative + " builds a Readiness and never refreshes it. A marker written once"
                            + " stays green for as long as the container's /tmp does, which is the"
                            + " half of this that a dead process would still pass");
        }
    }

    @Test
    void theProxyDoesNotBeatOnItsFailClosedPath() throws IOException {
        // A proxy with the gate off still binds its port, so only the marker can say it refuses logins.
        final String text = read(VELOCITY_PLUGIN);

        assertEquals(
                1,
                count(text, "startHeartbeat();"),
                VELOCITY_PLUGIN + " calls startHeartbeat() more than once; the fail-closed path is"
                        + " the one place it must not be called from");
        assertTrue(
                text.indexOf("startHeartbeat();") < text.indexOf("private void failClosed("),
                VELOCITY_PLUGIN + "'s only startHeartbeat() call is not inside start(...)");
        assertTrue(
                text.contains("heartbeat.cancel()"),
                VELOCITY_PLUGIN + " never cancels the beat, so a proxy on the way down keeps saying"
                        + " it is up for as long as its scheduler runs");
    }

    @Test
    void theBotBeatsOnlyAfterDiscordIsReadyAndBothReconcilesAreDone() throws IOException {
        final String text = read(BOT);

        // The constructor delegates: publishAndReconcile runs the reconciles, finishStartup builds the marker last.
        final int reconcileCall = text.indexOf("publishAndReconcile(");
        final int finishCall = text.indexOf("finishStartup(");
        final int up = text.indexOf("started = true;");
        final String reconcileBody = body(text, "private void publishAndReconcile(");
        final String finishBody = body(text, "private SignalHub finishStartup(");

        assertTrue(
                reconcileCall >= 0 && finishCall >= 0 && up >= 0,
                BOT + " is missing one of the three landmarks this test reads");
        assertTrue(
                reconcileBody.contains("roles().reconcile();"),
                BOT + "'s publishAndReconcile no longer runs the startup role reconcile");
        assertTrue(
                reconcileCall < finishCall,
                BOT + " builds its readiness marker before the startup"
                        + " reconcile, so a bot that dies during it would still have reported ready");
        assertTrue(
                finishCall < up,
                BOT + "'s readiness marker is built after `started = true`, which"
                        + " is the flag that decides whether the constructor cleaned up after itself");
        final int marker = finishBody.indexOf("Readiness.onDefaultPath(");
        assertTrue(
                marker >= 0 && finishBody.indexOf("listen(") < marker,
                BOT + "'s finishStartup does not build the readiness marker after everything else it starts");
        assertTrue(
                text.contains("repeat(guarded(\"readiness marker\""),
                BOT + " does not schedule its readiness beat the same way as every other duty.");
        final int repeatDeclaration = text.indexOf("void repeat(");
        assertTrue(repeatDeclaration >= 0, BOT + " has no repeat(...) helper any more");
        assertTrue(
                text.indexOf("timers.scheduleWithFixedDelay(", repeatDeclaration) >= 0,
                BOT + "'s repeat(...) does not beat on the existing timer executor. A pool of its own"
                        + " would keep reporting healthy while every scheduled duty this bot has was stuck.");
    }

    /** Returns the text of a method, from its declaration to the next private member. */
    private static String body(final String text, final String declaration) {
        final int from = text.indexOf(declaration);
        assertTrue(from >= 0, BOT + " has no " + declaration + "...) any more");
        final int to = text.indexOf("\n    private ", from + declaration.length());
        return text.substring(from, to < 0 ? text.length() : to);
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
        return joined(Files.readString(source, StandardCharsets.UTF_8));
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
        throw new IllegalStateException(
                "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }

    // palantir-java-format wraps a long call anywhere; the checks read each call as one line.
    private static String joined(final String source) {
        return source.replaceAll("\\(\\s*\\n\\s*", "(")
                .replaceAll("\\s*\\n\\s*\\.", ".")
                .replaceAll("(=|,|->)\\s*\\n\\s*", "$1 ");
    }
}
