package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.stewardagent.apply.ApplyResult;
import eu.nordtal.season.stewardagent.plugin.PluginDirectory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A run notes the file it moved into place for each artefact with its own release, and nothing else. */
class RunsRecordTest {

    private final List<String> noted = new ArrayList<>();

    private final PluginDirectory plugins = new PluginDirectory() {
        @Override
        public void installed(
                final String service, final String artifact, final String fileName, final String release) {
            noted.add(service + " " + artifact + " " + fileName + " " + release);
        }
    };

    private static final ApplyResult RESULT = new ApplyResult(List.of(
            new ApplyResult.Outcome(
                    "smp", "smp", ApplyResult.Status.DONE, "smp-0.10.3.jar -> smp-0.11.0.jar", "smp-0.11.0.jar"),
            new ApplyResult.Outcome("smp", "paper", ApplyResult.Status.DONE, "paper-26.2-12.jar", "paper-26.2-12.jar"),
            new ApplyResult.Outcome("smp", "voicechat", ApplyResult.Status.UNCHANGED, "voicechat-bukkit-2.6.1.jar"),
            new ApplyResult.Outcome("limbo", "limbo", ApplyResult.Status.FAILED, "could not move it"),
            new ApplyResult.Outcome("proxy", "resource-pack", ApplyResult.Status.DONE, "url and sha1")));

    @Test
    void everyFileMovedIntoPlaceIsNotedWithTheRunsRelease() {
        Runs.record(RESULT, "0.11.0", plugins);

        assertEquals(List.of("smp smp smp-0.11.0.jar 0.11.0", "smp paper paper-26.2-12.jar 0.11.0"), noted);
    }

    @Test
    void anAgentWithoutAReleaseNotesNothing() {
        Runs.record(RESULT, null, plugins);

        assertEquals(List.of(), noted);
    }
}
