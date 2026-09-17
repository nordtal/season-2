package eu.nordtal.s2.steward.worker.api;

import eu.nordtal.s2.steward.worker.configfile.ConfigDocument;
import eu.nordtal.s2.steward.worker.configfile.ConfigLocation;
import eu.nordtal.s2.steward.worker.docker.DockerException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three outcomes steward/59 asks a save not to blur together, and where "needs a restart" is
 * recorded.
 *
 * <p>Driven entirely through {@link ConfigApi.ConsoleLine}, a lambda rather than a real
 * {@code Docker} socket - which is what lets {@code NO_ANSWER} be exercised deterministically
 * instead of by hoping a container is not running.</p>
 */
class ConfigApiReloadTest {

    /** Records every call, so a test can also check what was actually sent. */
    private static final class RecordingConsole implements ConfigApi.ConsoleLine {
        private final List<String> calls = new ArrayList<>();
        private RuntimeException fail;

        @Override
        public void send(final String service, final String command) {
            calls.add(service + ": " + command);
            if (fail != null) {
                throw fail;
            }
        }
    }

    private static ConfigLocation location(final String service, final String name) {
        return new ConfigLocation(service, name, Path.of("/tmp/does-not-matter"), true, true);
    }

    @Test
    @DisplayName("a file no command reaches is RESTART_REQUIRED, and names the file")
    void restartRequiredWhenNoCommandIsKnown() {
        final RecordingConsole console = new RecordingConsole();
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), console);

        // smp/smp/config.yml binds worlds at enable - see ReloadSmp's own javadoc - so it is
        // deliberately absent from ConfigApi.RELOAD_COMMAND.
        final Map<String, Object> outcome = api.reload(location("smp", "smp/config.yml"));

        assertEquals("RESTART_REQUIRED", outcome.get("status"));
        assertTrue(String.valueOf(outcome.get("message")).contains("restart"),
                "the message has to say a restart is what is needed: " + outcome.get("message"));
        assertTrue(console.calls.isEmpty(), "nothing should have been sent to any console");
    }

    @Test
    @DisplayName("a reloadable file that a live console accepts is APPLIED")
    void appliedWhenTheConsoleAcceptsTheLine() {
        final RecordingConsole console = new RecordingConsole();
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), console);

        final Map<String, Object> outcome = api.reload(location("smp", "smp/milestones.yml"));

        assertEquals("APPLIED", outcome.get("status"));
        assertEquals(List.of("smp: smp reload"), console.calls);
    }

    @Test
    @DisplayName("colours.yml reloads too - /smp reload re-reads it (steward/73)")
    void theTonePaletteIsReloadable() {
        final RecordingConsole console = new RecordingConsole();
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), console);

        final Map<String, Object> outcome = api.reload(location("smp", "smp/colours.yml"));

        // season-2-ingame/22 made the five tone colours a file of their own, and SmpPlugin re-reads
        // it on `/smp reload`. This map was written the evening before that file existed, so the
        // interface told an operator to restart a server for a change a console line already
        // applies - and colours are picked by trying, which is where that costs the most.
        assertEquals("APPLIED", outcome.get("status"));
        assertEquals(List.of("smp: smp reload"), console.calls);
    }

    @Test
    @DisplayName("prestige-colours.yml reloads too - /smp reload re-reads it (season-2-ingame/23)")
    void thePrestigePaletteIsReloadable() {
        final RecordingConsole console = new RecordingConsole();
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), console);

        final Map<String, Object> outcome = api.reload(location("smp", "smp/prestige-colours.yml"));

        // Its own file beside colours.yml, and SmpPlugin re-reads it on `/smp reload` the same way
        // - see PlayerComposition's colours supplier. Without this line the interface would tell an
        // operator to restart a server for a colour change a console line already applies, the same
        // lie steward/73 fixed for the tone palette.
        assertEquals("APPLIED", outcome.get("status"));
        assertEquals(List.of("smp: smp reload"), console.calls);
    }

    @Test
    @DisplayName("a reloadable file whose service does not answer is NO_ANSWER, not APPLIED")
    void noAnswerWhenTheContainerCannotBeReached() {
        final RecordingConsole console = new RecordingConsole();
        console.fail = new DockerException("no running container for hunger-games");
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), console);

        final Map<String, Object> outcome =
                api.reload(location("hunger-games", "hunger-games/sounds.yml"));

        assertEquals("NO_ANSWER", outcome.get("status"));
        assertTrue(String.valueOf(outcome.get("message")).contains("did not answer"),
                "APPLIED and NO_ANSWER must not read alike: " + outcome.get("message"));
    }

    @Test
    @DisplayName("APPLIED and NO_ANSWER never share a status word")
    void appliedAndNoAnswerAreDistinguishable() {
        final RecordingConsole ok = new RecordingConsole();
        final RecordingConsole down = new RecordingConsole();
        down.fail = new DockerException("gone");
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), ok);
        final ConfigApi apiDown = new ConfigApi(Path.of("/tmp"), down);

        final Object applied = api.reload(location("smp", "smp/sounds.yml")).get("status");
        final Object noAnswer =
                apiDown.reload(location("smp", "smp/sounds.yml")).get("status");

        assertFalse(applied.equals(noAnswer), "the two outcomes must not collapse into one status");
    }

    @Test
    @DisplayName("a console that refuses the service by name still reads as needing a restart")
    void restartRequiredWhenTheConsoleItselfRefuses() {
        // Defensive: nothing in RELOAD_COMMAND today names a service without a console, but a
        // future edit to that map that got this wrong must not turn an already-successful save
        // into a 500 - it degrades to the same sentence an unknown file gets.
        final RecordingConsole console = new RecordingConsole();
        console.fail = new IllegalArgumentException("hunger-games has no console: reasons");
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), console);

        final Map<String, Object> outcome =
                api.reload(location("hunger-games", "hunger-games/sounds.yml"));

        assertEquals("RESTART_REQUIRED", outcome.get("status"));
    }

    @Test
    @DisplayName("the open form is told a restart is needed before anybody saves anything")
    void documentCarriesRestartRequiredForAFileNothingReloads() {
        final ConfigLocation loc = location("network-control", "network-control/network.yml");
        final ConfigDocument read = new ConfigDocument(loc.file(), "rev-1", List.of(), List.of());

        final Map<String, Object> document = ConfigApi.document(loc, read);

        assertEquals(Boolean.TRUE, document.get("restartRequired"));
    }

    @Test
    @DisplayName("the open form says no restart is needed for a file that does reload")
    void documentSaysNoRestartNeededForAReloadableFile() {
        final ConfigLocation loc = location("smp", "smp/milestones.yml");
        final ConfigDocument read = new ConfigDocument(loc.file(), "rev-1", List.of(), List.of());

        final Map<String, Object> document = ConfigApi.document(loc, read);

        assertEquals(Boolean.FALSE, document.get("restartRequired"));
    }
}
