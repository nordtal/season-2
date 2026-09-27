package eu.nordtal.s2.steward.worker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.steward.worker.configfile.ConfigDocument;
import eu.nordtal.s2.steward.worker.configfile.ConfigLocation;
import eu.nordtal.s2.steward.worker.docker.DockerException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The three outcomes a save must not blur together, and where "needs a restart" is recorded.
 *
 * A lambda console instead of a real Docker socket makes {@code NO_ANSWER} deterministic.
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
    void aFileNoCommandReachesIsRestartRequiredAndNamesTheFile() {
        final RecordingConsole console = new RecordingConsole();
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), console);

        // smp/smp/config.yml binds worlds at enable, so it is deliberately absent from ConfigApi.RELOAD_COMMAND.
        final Map<String, Object> outcome = api.reload(location("smp", "smp/config.yml"));

        assertEquals("RESTART_REQUIRED", outcome.get("status"));
        assertTrue(
                String.valueOf(outcome.get("message")).contains("restart"),
                "the message has to say a restart is what is needed: " + outcome.get("message"));
        assertTrue(console.calls.isEmpty(), "nothing should have been sent to any console");
    }

    @Test
    void aReloadableFileThatALiveConsoleAcceptsIsApplied() {
        final RecordingConsole console = new RecordingConsole();
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), console);

        final Map<String, Object> outcome = api.reload(location("smp", "smp/milestones.yml"));

        assertEquals("APPLIED", outcome.get("status"));
        assertEquals(List.of("smp: smp reload"), console.calls);
    }

    @Test
    void coloursYmlReloadsBecauseSmpReloadReReadsIt() {
        final RecordingConsole console = new RecordingConsole();
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), console);

        final Map<String, Object> outcome = api.reload(location("smp", "smp/colours.yml"));

        // SmpPlugin re-reads the five tone colours on `/smp reload`, so a restart prompt here would be a lie.
        assertEquals("APPLIED", outcome.get("status"));
        assertEquals(List.of("smp: smp reload"), console.calls);
    }

    @Test
    void prestigeYmlReloadsBecauseSmpReloadReReadsHoursAndColours() {
        final RecordingConsole console = new RecordingConsole();
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), console);

        final Map<String, Object> outcome = api.reload(location("smp", "smp/prestige.yml"));

        // Its own file beside colours.yml; SmpPlugin re-reads it on `/smp reload` the same way, so no restart prompt.
        assertEquals("APPLIED", outcome.get("status"));
        assertEquals(List.of("smp: smp reload"), console.calls);
    }

    @Test
    void aReloadableFileWhoseServiceDoesNotAnswerIsNoAnswerNotApplied() {
        final RecordingConsole console = new RecordingConsole();
        console.fail = new DockerException("no running container for hunger-games");
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), console);

        final Map<String, Object> outcome = api.reload(location("hunger-games", "hunger-games/sounds.yml"));

        assertEquals("NO_ANSWER", outcome.get("status"));
        assertTrue(
                String.valueOf(outcome.get("message")).contains("did not answer"),
                "APPLIED and NO_ANSWER must not read alike: " + outcome.get("message"));
    }

    @Test
    void appliedAndNoAnswerNeverShareAStatusWord() {
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
    void aConsoleThatRefusesTheServiceByNameStillReadsAsNeedingARestart() {
        // Defensive: a RELOAD_COMMAND entry naming a service with no console must degrade, not turn a save into a 500.
        final RecordingConsole console = new RecordingConsole();
        console.fail = new IllegalArgumentException("hunger-games has no console: reasons");
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), console);

        final Map<String, Object> outcome = api.reload(location("hunger-games", "hunger-games/sounds.yml"));

        assertEquals("RESTART_REQUIRED", outcome.get("status"));
    }

    @Test
    void theOpenFormIsToldARestartIsNeededBeforeAnybodySavesAnything() {
        final ConfigLocation loc = location("proxy", "proxy/network.yml");
        final ConfigDocument read = new ConfigDocument(loc.file(), "rev-1", List.of(), List.of());

        final Map<String, Object> document = ConfigApi.document(loc, read);

        assertEquals(true, document.get("restartRequired"));
    }

    @Test
    void theOpenFormSaysNoRestartIsNeededForAFileThatDoesReload() {
        final ConfigLocation loc = location("smp", "smp/milestones.yml");
        final ConfigDocument read = new ConfigDocument(loc.file(), "rev-1", List.of(), List.of());

        final Map<String, Object> document = ConfigApi.document(loc, read);

        assertEquals(false, document.get("restartRequired"));
    }

    @Test
    void thisWorkersOwnStewardYmlIsReadAgainByThisProcessNotByAConsole() {
        final RecordingConsole console = new RecordingConsole();
        final int[] reread = {0};
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), console, Map.of(ConfigApi.OWN_CONFIG, () -> reread[0]++));

        final Map<String, Object> outcome = api.reload(location("steward-worker", "steward.yml"));

        assertEquals("APPLIED", outcome.get("status"));
        assertEquals(1, reread[0], "the schedule has to be re-read on the save itself");
        assertTrue(console.calls.isEmpty(), "steward-worker has no console line to send");
    }

    @Test
    void aReReadThatFailsLeavesTheSaveStandingAndSaysARestartPicksItUp() {
        final ConfigApi api =
                new ConfigApi(Path.of("/tmp"), new RecordingConsole(), Map.of(ConfigApi.OWN_CONFIG, () -> {
                    throw new IllegalStateException("backup.patience-minutes must be positive");
                }));

        final Map<String, Object> outcome = api.reload(location("steward-worker", "steward.yml"));

        assertEquals("RESTART_REQUIRED", outcome.get("status"));
        assertTrue(
                String.valueOf(outcome.get("message")).contains("patience-minutes"),
                String.valueOf(outcome.get("message")));
    }
}
