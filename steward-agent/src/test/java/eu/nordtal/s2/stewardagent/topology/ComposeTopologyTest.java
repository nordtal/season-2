package eu.nordtal.s2.stewardagent.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What {@code compose config} says about the services, read the way a backup and a restore need it. */
class ComposeTopologyTest {

    /** Two servers, the bot, and the agent mounting what a backup saves, as compose config prints them. */
    private static final String SERVICES = """
            {
              "smp": {"image": "mc", "labels": {"eu.nordtal.backup": "stop"}, "volumes": [
                {"type": "bind", "source": "/srv/n/mc-smp", "target": "/data"},
                {"type": "bind", "source": "/srv/n/mc-smp-plugins", "target": "/data/plugins"}]},
              "hunger-games": {"image": "mc", "volumes": [
                {"type": "bind", "source": "/srv/n/mc-hunger-games-plugins", "target": "/data/plugins"}]},
              "discord-bot": {"image": "bot", "volumes": [
                {"type": "volume", "source": "bot-config", "target": "/app/config"}]},
              "steward-agent": {"image": "agent", "volumes": [
                {"type": "bind", "source": "/srv/n/mc-smp-plugins", "target": "/volumes/smp/plugins"},
                {"type": "bind", "source": "/srv/n/mc-smp", "target": "/backup-sources/nordtal-s2_mc-smp"},
                {"type": "bind", "source": "/srv/n/mc-smp-plugins", "target": "/backup-sources/nordtal-s2_mc-smp-plugins"},
                {"type": "bind", "source": "/srv/n/mc-hunger-games-plugins",
                 "target": "/backup-sources/nordtal-s2_mc-hunger-games-plugins"},
                {"type": "volume", "source": "bot-config", "target": "/backup-sources/nordtal-s2_bot-config"}]}
            }
            """;

    private final AgentWire.Topology topology =
            ComposeTopology.parse(JsonParser.parseString(SERVICES).getAsJsonObject(), "/backup-sources");

    @Test
    void aSavedVolumeNamesTheServicesThatRunOnItAndNeverTheAgent() {
        assertEquals(List.of("smp"), topology.usersOf("nordtal-s2_mc-smp"));
        assertEquals(List.of("smp"), topology.usersOf("nordtal-s2_mc-smp-plugins"));
        assertEquals(List.of("hunger-games"), topology.usersOf("nordtal-s2_mc-hunger-games-plugins"));
        assertEquals(List.of("discord-bot"), topology.usersOf("nordtal-s2_bot-config"));
    }

    @Test
    void theBackupStillStopsOnlyWhatItsLabelSays() {
        assertEquals(List.of("smp"), topology.stoppedForBackup());
        assertEquals(4, topology.backupVolumes().size());
    }

    @Test
    void aVolumeNothingRunsOnHasNoUsers() {
        assertEquals(List.of(), topology.usersOf("nordtal-s2_unknown"));
        assertEquals(
                List.of(),
                ComposeTopology.parse(new JsonObject(), "/backup-sources").usersOf("x"));
    }
}
