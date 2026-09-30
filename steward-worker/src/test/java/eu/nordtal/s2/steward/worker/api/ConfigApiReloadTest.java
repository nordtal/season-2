package eu.nordtal.s2.steward.worker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.steward.worker.configfile.ConfigDocument;
import eu.nordtal.s2.steward.worker.configfile.ConfigLocation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The three outcomes a save must not blur together, and where "needs a restart" is recorded.
 *
 * A recording reloader instead of a real inbox makes {@code NO_ANSWER} deterministic.
 */
class ConfigApiReloadTest {

    /** Records every service asked, and answers as told: applied, not applied, silent or refusing the service. */
    private static final class RecordingReloader implements ConfigApi.Reloader {
        private final List<String> calls = new ArrayList<>();
        private RuntimeException fail;
        private Optional<ConfigApi.Reloaded> answer = Optional.of(new ConfigApi.Reloaded(true, "reloaded"));

        @Override
        public Optional<ConfigApi.Reloaded> reload(final String service) {
            calls.add(service);
            if (fail != null) {
                throw fail;
            }
            return answer;
        }
    }

    private static ConfigLocation location(final String service, final String name) {
        return new ConfigLocation(service, name, Path.of("/tmp/does-not-matter"), true, true);
    }

    @Test
    void aFileNoReloadReachesIsRestartRequiredAndNamesTheFile() {
        final RecordingReloader reloader = new RecordingReloader();
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), reloader);

        // smp/smp/config.yml binds worlds at enable, so it is deliberately absent from ConfigApi.RELOADABLE.
        final Map<String, Object> outcome = api.reload(location("smp", "smp/config.yml"));

        assertEquals("RESTART_REQUIRED", outcome.get("status"));
        assertTrue(
                String.valueOf(outcome.get("message")).contains("restart"),
                "the message has to say a restart is what is needed: " + outcome.get("message"));
        assertTrue(reloader.calls.isEmpty(), "no service should have been asked");
    }

    @Test
    void aReloadableFileThatTheServiceReReadsIsApplied() {
        final RecordingReloader reloader = new RecordingReloader();
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), reloader);

        final Map<String, Object> outcome = api.reload(location("smp", "smp/milestones.yml"));

        assertEquals("APPLIED", outcome.get("status"));
        assertEquals(List.of("smp"), reloader.calls);
    }

    @Test
    void coloursYmlReloadsBecauseSmpReloadReReadsIt() {
        final RecordingReloader reloader = new RecordingReloader();
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), reloader);

        final Map<String, Object> outcome = api.reload(location("smp", "smp/colours.yml"));

        // The plugin base re-reads the five tone colours on every reload, so a restart prompt here would be a lie.
        assertEquals("APPLIED", outcome.get("status"));
        assertEquals(List.of("smp"), reloader.calls);
    }

    @Test
    void prestigeYmlReloadsBecauseSmpReloadReReadsHoursAndColours() {
        final RecordingReloader reloader = new RecordingReloader();
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), reloader);

        final Map<String, Object> outcome = api.reload(location("smp", "smp/prestige.yml"));

        // Its own file beside colours.yml, which the plugin base re-reads on every reload the same way.
        assertEquals("APPLIED", outcome.get("status"));
        assertEquals(List.of("smp"), reloader.calls);
    }

    @Test
    void aReloadableFileWhoseServiceDoesNotAnswerIsNoAnswerNotApplied() {
        final RecordingReloader reloader = new RecordingReloader();
        reloader.answer = Optional.empty();
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), reloader);

        final Map<String, Object> outcome = api.reload(location("hunger-games", "hunger-games/sounds.yml"));

        assertEquals("NO_ANSWER", outcome.get("status"));
        assertTrue(
                String.valueOf(outcome.get("message")).contains("did not answer"),
                "APPLIED and NO_ANSWER must not read alike: " + outcome.get("message"));
    }

    @Test
    void aServiceThatReReadOnlyPartOfItSaysWhatItDidNotTake() {
        final RecordingReloader reloader = new RecordingReloader();
        reloader.answer = Optional.of(new ConfigApi.Reloaded(false, "the prestige name colours: not a colour"));
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), reloader);

        final Map<String, Object> outcome = api.reload(location("smp", "smp/prestige.yml"));

        assertEquals("NO_ANSWER", outcome.get("status"));
        assertTrue(
                String.valueOf(outcome.get("message")).contains("the prestige name colours: not a colour"),
                "the service's own words are what tells the admin which file to fix: " + outcome.get("message"));
    }

    @Test
    void appliedAndNoAnswerNeverShareAStatusWord() {
        final RecordingReloader ok = new RecordingReloader();
        final RecordingReloader down = new RecordingReloader();
        down.answer = Optional.empty();
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), ok);
        final ConfigApi apiDown = new ConfigApi(Path.of("/tmp"), down);

        final Object applied = api.reload(location("smp", "smp/sounds.yml")).get("status");
        final Object noAnswer =
                apiDown.reload(location("smp", "smp/sounds.yml")).get("status");

        assertFalse(applied.equals(noAnswer), "the two outcomes must not collapse into one status");
    }

    @Test
    void aServiceWithNoInboxStillReadsAsNeedingARestart() {
        // Defensive: a reloadable file of a service with no inbox must degrade, not turn a save into a 500.
        final RecordingReloader reloader = new RecordingReloader();
        reloader.fail = new IllegalArgumentException("hunger-games has no inbox: reasons");
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), reloader);

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
    void thisWorkersOwnStewardYmlIsReadAgainByThisProcessNotByAnInbox() {
        final RecordingReloader reloader = new RecordingReloader();
        final int[] reread = {0};
        final ConfigApi api = new ConfigApi(Path.of("/tmp"), reloader, Map.of(ConfigApi.OWN_CONFIG, () -> reread[0]++));

        final Map<String, Object> outcome = api.reload(location("steward-worker", "steward.yml"));

        assertEquals("APPLIED", outcome.get("status"));
        assertEquals(1, reread[0], "the schedule has to be re-read on the save itself");
        assertTrue(reloader.calls.isEmpty(), "steward-worker asks no inbox for its own file");
    }

    @Test
    void aReReadThatFailsLeavesTheSaveStandingAndSaysARestartPicksItUp() {
        final ConfigApi api =
                new ConfigApi(Path.of("/tmp"), new RecordingReloader(), Map.of(ConfigApi.OWN_CONFIG, () -> {
                    throw new IllegalStateException("backup.patience-minutes must be positive");
                }));

        final Map<String, Object> outcome = api.reload(location("steward-worker", "steward.yml"));

        assertEquals("RESTART_REQUIRED", outcome.get("status"));
        assertTrue(
                String.valueOf(outcome.get("message")).contains("patience-minutes"),
                String.valueOf(outcome.get("message")));
    }
}
