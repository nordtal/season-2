package eu.nordtal.season.stewardagent.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.Topology;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    @Test
    void aServersLabelsSayItsKindItsPluginsTheirJarPrefixesAndWhichItMayLack() {
        final AgentWire.Topology read = parse("""
                {"smp": {"labels": {"eu.nordtal.server": "paper",
                  "eu.nordtal.plugins": "smp display-tags=papermc-display-tags voicechat? tags=other-tags?"}}}
                """);

        final Topology.Service smp = read.servers().getFirst();
        assertEquals(Topology.Kind.PAPER, smp.kind());
        assertEquals(List.of("smp", "display-tags", "voicechat", "tags"), smp.plugins());
        assertEquals(List.of("voicechat", "tags"), smp.optional());
        assertEquals(Map.of("display-tags", "papermc-display-tags", "tags", "other-tags"), smp.prefixes());
        assertEquals("papermc-display-tags", smp.prefixOf("display-tags"));
        assertEquals("smp", smp.prefixOf("smp"));
    }

    @Test
    void aServiceWithoutTheServerLabelHasNoPluginsFolder() {
        final AgentWire.Topology read = parse("""
                {"postgres": {"labels": {"eu.nordtal.renew": "last"}}, "proxy": {"labels":
                  {"eu.nordtal.server": "velocity", "eu.nordtal.plugins": "proxy"}}}
                """);

        assertEquals(
                List.of("proxy"),
                read.servers().stream().map(Topology.Service::name).toList());
        assertTrue(read.hasPlugins("proxy"));
        assertFalse(read.hasPlugins("postgres"));
        assertNull(read.services().getFirst().server());
    }

    @Test
    void aStandbyNamesTheServiceItStandsInFor() {
        final AgentWire.Topology read = parse("""
                {"proxy": {}, "proxy-standby": {"labels": {"eu.nordtal.standby-of": "proxy"}}, "limbo": {},
                 "limbo-standby": {"labels": {"eu.nordtal.standby-of": "limbo"}}}
                """);

        assertEquals(List.of("proxy-standby", "limbo-standby"), read.standbys());
        assertEquals(Optional.of("limbo-standby"), read.standbyOf("limbo"));
        assertEquals(Optional.empty(), read.standbyOf("smp"));
    }

    @Test
    void theFourPictureLabelsBecomeAWiringAndAServiceWithoutASectionIsNotDrawn() {
        final AgentWire.Topology read = parse("""
                {"caddy": {"labels": {"eu.nordtal.section": "Entry", "eu.nordtal.entry": "true",
                   "eu.nordtal.reaches": "steward"}},
                 "steward": {"labels": {"eu.nordtal.section": " Steward ", "eu.nordtal.reaches": "agent  bunq",
                   "eu.nordtal.stores-in": "postgres"}},
                 "migrate": {}}
                """);

        assertEquals(
                new AgentWire.Wiring("Entry", true, List.of("steward"), List.of()),
                read.services().get(0).wiring());
        assertEquals(
                new AgentWire.Wiring("Steward", false, List.of("agent", "bunq"), List.of("postgres")),
                read.services().get(1).wiring());
        assertNull(read.services().get(2).wiring());
    }

    /** A wire to a service the page does not draw would end nowhere, so every name a wiring gives is drawn. */
    @Test
    void everyServiceTheRepositorysWiringNamesIsDrawn() {
        final List<AgentWire.Service> drawn = ComposeFile.topology().services().stream()
                .filter(service -> service.wiring() != null)
                .toList();
        final List<String> names = drawn.stream().map(AgentWire.Service::name).toList();

        assertTrue(drawn.size() > 1, "compose.yml draws the network");
        for (final AgentWire.Service service : drawn) {
            final AgentWire.Wiring wiring = java.util.Objects.requireNonNull(service.wiring());
            for (final String target : java.util.stream.Stream.concat(
                            wiring.reaches().stream(), wiring.storesIn().stream())
                    .toList()) {
                assertTrue(names.contains(target), service.name() + " is wired to " + target + ", which is not drawn");
            }
        }
    }

    @Test
    void theRenewLabelSaysWhenARunMakesAServiceAgainInFileOrder() {
        final AgentWire.Topology read = parse("""
                {"postgres": {"labels": {"eu.nordtal.renew": "last"}}, "smp": {"labels": {"eu.nordtal.renew": "run"}},
                 "caddy": {"labels": {"eu.nordtal.renew": "after"}}, "pack-host": {"labels": {"eu.nordtal.renew": "after"}},
                 "migrate": {}}
                """);

        assertEquals(List.of("smp"), read.renewed(AgentWire.Renewal.RUN));
        assertEquals(List.of("caddy", "pack-host"), read.renewed(AgentWire.Renewal.AFTER));
        assertEquals(List.of("postgres"), read.renewed(AgentWire.Renewal.LAST));
    }

    @Test
    void aLabelNoneOfThisUnderstandsIsRefusedRatherThanReadAsNothing() {
        assertThrows(IllegalStateException.class, () -> parse("""
                {"smp": {"labels": {"eu.nordtal.server": "spigot"}}}
                """));
        assertThrows(IllegalStateException.class, () -> parse("""
                {"smp": {"labels": {"eu.nordtal.server": "paper", "eu.nordtal.plugins": "smp tags="}}}
                """));
        assertThrows(IllegalStateException.class, () -> parse("""
                {"smp": {"labels": {"eu.nordtal.renew": "sometimes"}}}
                """));
    }

    private static AgentWire.Topology parse(final String services) {
        return ComposeTopology.parse(JsonParser.parseString(services).getAsJsonObject(), "/backup-sources");
    }
}
