package eu.nordtal.s2.steward.worker.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.steward.worker.plugin.ManagedPlugin;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link Topology#servicesWith}, where the fixed plugin lists and the {@code service_plugin} rows become one list. */
class TopologyMergeTest {

    private static ManagedPlugin added(final String service, final String slug) {
        return new ManagedPlugin(service, slug, "AbCdEf01", slug + "-bukkit", slug, null, null, Instant.EPOCH, "till");
    }

    private static Topology.Service find(final List<Topology.Service> services, final String name) {
        return services.stream()
                .filter(service -> service.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void noRowsIsTheListTheCodeGivesUnchangedAndNotCopied() {
        assertSame(Topology.SERVICES, Topology.servicesWith(List.of()));
    }

    @Test
    void anAddedPluginJoinsItsServicesPluginsAndNothingElses() {
        final List<Topology.Service> merged = Topology.servicesWith(List.of(added(Topology.SMP, "worldedit")));

        assertTrue(find(merged, Topology.SMP).plugins().contains("worldedit"));
        assertFalse(find(merged, Topology.LIMBO).plugins().contains("worldedit"));
        assertFalse(find(merged, Topology.PROXY).plugins().contains("worldedit"));
        // The fixed rows are still there and still first; reordering them would reorder every report.
        assertEquals(Topology.SMP, find(merged, Topology.SMP).plugins().getFirst());
    }

    @Test
    void andItIsOptionalSoAMissingBuildCanNeverKeepAServerFromStarting() {
        final List<Topology.Service> merged = Topology.servicesWith(List.of(added(Topology.SMP, "worldedit")));

        final Topology.Service smp = find(merged, Topology.SMP);
        assertTrue(smp.optional().contains("worldedit"));
        // guarded() is EXPECTED_PLUGINS: an added plugin must not hold the SMP for lacking a build, like CoreProtect.
        assertFalse(smp.guarded().contains("worldedit"));
        assertTrue(smp.guarded().contains(Topology.SMP));
        assertTrue(smp.guarded().contains(Topology.DISPLAY_TAGS));
    }

    @Test
    void onTheProxyTheArtefactIdCarriesTheLoaderAsVoicechatVelocityAlreadyDoes() {
        final List<Topology.Service> merged = Topology.servicesWith(
                List.of(added(Topology.PROXY, "simple-voice-chat"), added(Topology.SMP, "simple-voice-chat")));

        // One Modrinth project, two jars: without the suffix both resolve to one artefact id, whichever wins.
        assertTrue(find(merged, Topology.PROXY).plugins().contains("simple-voice-chat-velocity"));
        assertFalse(find(merged, Topology.PROXY).plugins().contains("simple-voice-chat"));
        assertTrue(find(merged, Topology.SMP).plugins().contains("simple-voice-chat"));
    }

    @Test
    void aRowNamingAServiceThatDoesNotExistIsIgnoredNotRefused() {
        // A row addressed to a service name that no longer exists must not fail the resolve for the other services.
        final List<Topology.Service> merged = Topology.servicesWith(List.of(added("network-control", "worldedit")));

        assertEquals(Topology.SERVICES.size(), merged.size());
        merged.forEach(service -> assertFalse(
                service.plugins().contains("worldedit"),
                service.name() + " picked up a row addressed to a service that does not exist"));
    }

    @Test
    void aRowForAPluginTheCodeAlreadyGivesChangesNothingTheFixedEntryWins() {
        final List<Topology.Service> merged =
                Topology.servicesWith(List.of(added(Topology.SMP, Topology.PACKETEVENTS)));

        final Topology.Service smp = find(merged, Topology.SMP);
        assertEquals(
                1,
                smp.plugins().stream().filter(Topology.PACKETEVENTS::equals).count(),
                "packetevents appears twice, so it would be resolved twice and fought over on disk");
        // It keeps the fixed rows guardedness too: PacketEvents is required on the SMP, a table row cannot demote it.
        assertTrue(smp.guarded().contains(Topology.PACKETEVENTS));
    }

    @Test
    void theLoaderModrinthIsAskedWithIsKeptApartFromTheFillApisProjectName() {
        assertEquals("paper", Topology.Kind.PAPER.modrinthLoader());
        assertEquals("velocity", Topology.Kind.VELOCITY.modrinthLoader());
        assertEquals("paper", Topology.Kind.PAPER.fillProject());
        assertEquals("velocity", Topology.Kind.VELOCITY.fillProject());
    }
}
