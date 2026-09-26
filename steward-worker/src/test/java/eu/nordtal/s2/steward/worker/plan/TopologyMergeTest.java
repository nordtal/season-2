package eu.nordtal.s2.steward.worker.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.plugin.ManagedPlugin;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Topology#servicesWith} - where the {@code List.of(...)} in the code and the rows in
 * {@code service_plugin} become one list (season-2-ops/129).
 *
 * <p>This method is the feature. Everything the ticket asks for downstream - a plugin added in the
 * browser running in the update cycle, a Nordtal plugin that cannot be removed - is a consequence
 * of what this returns, which is why it is tested here rather than through a resolve.</p>
 */
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
    @DisplayName("no rows is the list the code gives, unchanged and not copied")
    void nothingAddedChangesNothing() {
        assertSame(Topology.SERVICES, Topology.servicesWith(List.of()));
    }

    @Test
    @DisplayName("an added plugin joins its service's plugins and nothing else's")
    void oneRowLandsOnOneService() {
        final List<Topology.Service> merged = Topology.servicesWith(List.of(added(Topology.SMP, "worldedit")));

        assertTrue(find(merged, Topology.SMP).plugins().contains("worldedit"));
        assertFalse(find(merged, Topology.LIMBO).plugins().contains("worldedit"));
        assertFalse(find(merged, Topology.PROXY).plugins().contains("worldedit"));
        // The fixed rows are still there and still first - a merge that reordered them would
        // reorder every report.
        assertEquals(Topology.SMP, find(merged, Topology.SMP).plugins().getFirst());
    }

    @Test
    @DisplayName("and it is optional, so a missing build can never keep a server from starting")
    void everyAddedPluginIsOptional() {
        final List<Topology.Service> merged = Topology.servicesWith(List.of(added(Topology.SMP, "worldedit")));

        final Topology.Service smp = find(merged, Topology.SMP);
        assertTrue(smp.optional().contains("worldedit"));
        // guarded() is what compose's EXPECTED_PLUGINS asks for. A plugin somebody added on a
        // Tuesday must never be able to hold the SMP down because its author has not shipped a
        // build for the next Minecraft drop - that is exactly CoreProtect's reasoning, applied to
        // the general case.
        assertFalse(smp.guarded().contains("worldedit"));
        assertTrue(smp.guarded().contains(Topology.SMP));
        assertTrue(smp.guarded().contains(Topology.DISPLAY_TAGS));
    }

    @Test
    @DisplayName("on the proxy the artefact id carries the loader, as voicechat-velocity already does")
    void velocityGetsItsOwnArtefactId() {
        final List<Topology.Service> merged = Topology.servicesWith(
                List.of(added(Topology.PROXY, "simple-voice-chat"), added(Topology.SMP, "simple-voice-chat")));

        // One Modrinth project, two jars that move separately. Without the suffix both services
        // would resolve under one artefact id and whichever was asked last would win - a Velocity
        // jar in a Paper plugins folder, or the other way round.
        assertTrue(find(merged, Topology.PROXY).plugins().contains("simple-voice-chat-velocity"));
        assertFalse(find(merged, Topology.PROXY).plugins().contains("simple-voice-chat"));
        assertTrue(find(merged, Topology.SMP).plugins().contains("simple-voice-chat"));
    }

    @Test
    @DisplayName("a row naming a service that does not exist is ignored, not refused")
    void anUnknownServiceIsSkipped() {
        // `network-control` was renamed to `proxy` on 2026-09-19 (season-2-ops/117). A leftover row
        // must not be able to fail the resolve for the other four services.
        final List<Topology.Service> merged = Topology.servicesWith(List.of(added("network-control", "worldedit")));

        assertEquals(Topology.SERVICES.size(), merged.size());
        merged.forEach(service -> assertFalse(
                service.plugins().contains("worldedit"),
                service.name() + " picked up a row addressed to a service that does not exist"));
    }

    @Test
    @DisplayName("a row for a plugin the code already gives changes nothing - the fixed entry wins")
    void theFixedListWins() {
        final List<Topology.Service> merged =
                Topology.servicesWith(List.of(added(Topology.SMP, Topology.PACKETEVENTS)));

        final Topology.Service smp = find(merged, Topology.SMP);
        assertEquals(
                1,
                smp.plugins().stream().filter(Topology.PACKETEVENTS::equals).count(),
                "packetevents appears twice, so it would be resolved twice and fought over on disk");
        // And it keeps the fixed row's guardedness: the code says the SMP needs PacketEvents, and a row
        // in a table must not be able to demote that.
        assertTrue(smp.guarded().contains(Topology.PACKETEVENTS));
    }

    @Test
    @DisplayName("the loader Modrinth is asked with is kept apart from the Fill API's project name")
    void theTwoVocabulariesAreSeparateFields() {
        assertEquals("paper", Topology.Kind.PAPER.modrinthLoader());
        assertEquals("velocity", Topology.Kind.VELOCITY.modrinthLoader());
        assertEquals("paper", Topology.Kind.PAPER.fillProject());
        assertEquals("velocity", Topology.Kind.VELOCITY.fillProject());
    }
}
