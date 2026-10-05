package eu.nordtal.season.stewardagent.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.stewardagent.topology.DeclaredTopology;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link PluginDirectory#servicesWith}, where the fixed plugin lists and the {@code service_plugin} rows meet. */
class TopologyMergeTest {

    private static final List<Topology.Service> SERVERS =
            DeclaredTopology.topology().servers();

    private static List<Topology.Service> servicesWith(final List<ManagedPlugin> added) {
        return PluginDirectory.servicesWith(SERVERS, added);
    }

    private static ManagedPlugin added(final String service, final String slug) {
        return new ManagedPlugin(
                service, slug, "AbCdEf01", slug + "-bukkit", slug, null, null, Instant.EPOCH, Actor.STEWARD);
    }

    private static Topology.Service find(final List<Topology.Service> services, final String name) {
        return services.stream()
                .filter(service -> service.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void noRowsIsTheListTheCodeGivesUnchangedAndNotCopied() {
        assertSame(SERVERS, servicesWith(List.of()));
    }

    @Test
    void anAddedPluginJoinsItsServicesPluginsAndNothingElses() {
        final List<Topology.Service> merged = servicesWith(List.of(added(Topology.SMP, "worldedit")));

        assertTrue(find(merged, Topology.SMP).plugins().contains("worldedit"));
        assertFalse(find(merged, Topology.LIMBO).plugins().contains("worldedit"));
        assertFalse(find(merged, Topology.PROXY).plugins().contains("worldedit"));
        // The fixed rows are still there and still first; reordering them would reorder every report.
        assertEquals(Topology.SMP, find(merged, Topology.SMP).plugins().getFirst());
    }

    @Test
    void andItIsOptionalSoAMissingBuildCanNeverKeepAServerFromStarting() {
        final List<Topology.Service> merged = servicesWith(List.of(added(Topology.SMP, "worldedit")));

        final Topology.Service smp = find(merged, Topology.SMP);
        assertTrue(smp.optional().contains("worldedit"));
        // An added plugin must not hold the SMP for lacking a build, like CoreProtect; the label's own stay required.
        assertFalse(smp.optional().contains(Topology.SMP));
        assertFalse(smp.optional().contains(Topology.PACKETEVENTS));
        // The jar prefixes the label gives survive the merge, since the plugins tab names a jar by them.
        assertEquals("packetevents-spigot", smp.prefixOf(Topology.PACKETEVENTS));
    }

    @Test
    void onTheProxyTheArtefactIdCarriesTheLoaderAsVoicechatVelocityAlreadyDoes() {
        final List<Topology.Service> merged = servicesWith(
                List.of(added(Topology.PROXY, "simple-voice-chat"), added(Topology.SMP, "simple-voice-chat")));

        // One Modrinth project, two jars: without the suffix both resolve to one artefact id, whichever wins.
        assertTrue(find(merged, Topology.PROXY).plugins().contains("simple-voice-chat-velocity"));
        assertFalse(find(merged, Topology.PROXY).plugins().contains("simple-voice-chat"));
        assertTrue(find(merged, Topology.SMP).plugins().contains("simple-voice-chat"));
    }

    @Test
    void aRowNamingAServiceThatDoesNotExistIsIgnoredNotRefused() {
        // A row addressed to a service name that no longer exists must not fail the resolve for the other services.
        final List<Topology.Service> merged = servicesWith(List.of(added("network-control", "worldedit")));

        assertEquals(SERVERS.size(), merged.size());
        merged.forEach(service -> assertFalse(
                service.plugins().contains("worldedit"),
                service.name() + " picked up a row addressed to a service that does not exist"));
    }

    @Test
    void aRowForAPluginTheCodeAlreadyGivesChangesNothingTheFixedEntryWins() {
        final List<Topology.Service> merged = servicesWith(List.of(added(Topology.SMP, Topology.PACKETEVENTS)));

        final Topology.Service smp = find(merged, Topology.SMP);
        assertEquals(
                1,
                smp.plugins().stream().filter(Topology.PACKETEVENTS::equals).count(),
                "packetevents appears twice, so it would be resolved twice and fought over on disk");
        // It keeps the fixed rows guardedness too: PacketEvents is required on the SMP, a table row cannot demote it.
        assertFalse(smp.optional().contains(Topology.PACKETEVENTS));
    }

    @Test
    void theLoaderModrinthIsAskedWithIsKeptApartFromTheFillApisProjectName() {
        assertEquals("paper", Topology.Kind.PAPER.modrinthLoader());
        assertEquals("velocity", Topology.Kind.VELOCITY.modrinthLoader());
        assertEquals("paper", Topology.Kind.PAPER.fillProject());
        assertEquals("velocity", Topology.Kind.VELOCITY.fillProject());
    }
}
