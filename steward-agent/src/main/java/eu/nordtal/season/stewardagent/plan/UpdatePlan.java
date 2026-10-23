package eu.nordtal.season.stewardagent.plan;

import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.messages.MessageRef;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a run would do. Resolving produces it and writes nothing.
 *
 * @param seasonTag the resolved release tag, printed even when nothing changed
 * @param seasonPrerelease true only when an operator pinned a pre-release by tag
 * @param unclaimed jars in a {@code plugins/} folder no row accounts for, never touched and always reported
 * @param notes findings that belong to no single service, which only {@link Work} reads
 */
public record UpdatePlan(
        Instant resolvedAt,
        @Nullable String seasonTag,
        boolean seasonPrerelease,
        List<Change> changes,
        List<Unclaimed> unclaimed,
        List<MessageRef> notes) {

    public record Unclaimed(String service, String fileName) {}

    /**
     * Returns the row that makes a run leave {@code service} alone, or {@code null} when the run applies its work.
     *
     * Any failure but the server jar or the pack blocks it; the applier, report and plugin list all read this.
     */
    public @Nullable Change blocker(final String service) {
        return blocker(changes.stream()
                .filter(change -> service.equals(change.service()))
                .toList());
    }

    public static @Nullable Change blocker(final Collection<Change> serviceChanges) {
        return serviceChanges.stream()
                .filter(change -> change.status().isFailure())
                .filter(change -> !Topology.PAPER.equals(change.artifact()))
                .filter(change -> !Topology.VELOCITY.equals(change.artifact()))
                .filter(change -> !Topology.RESOURCE_PACK.equals(change.artifact()))
                .findFirst()
                .orElse(null);
    }

    public boolean hasWork() {
        return changes.stream().anyMatch(change -> change.status().isWork());
    }

    public boolean hasFailures() {
        return changes.stream().anyMatch(change -> change.status().isFailure());
    }

    public List<Change> withStatus(final Change.Status status) {
        return changes.stream().filter(change -> change.status() == status).toList();
    }

    /**
     * Returns the plan reduced to what a bootstrap installs: {@link Change.Status#MISSING} and every unresolved row.
     *
     * A bootstrap never upgrades. Unresolved rows stay, so an outage empties the folder visibly.
     */
    public UpdatePlan onlyMissing() {
        final List<Change> keep = changes.stream()
                .filter(change -> change.status() == Change.Status.MISSING
                        || change.status().isFailure())
                .toList();
        return new UpdatePlan(resolvedAt, seasonTag, seasonPrerelease, keep, unclaimed, notes);
    }

    /**
     * Returns the plan for some of the services, without the resource pack.
     *
     * @param services compose service names, empty returns the plan unchanged, because empty means the whole network
     */
    public UpdatePlan onlyServices(final java.util.Collection<String> services) {
        if (services.isEmpty()) {
            return this;
        }
        final java.util.Set<String> wanted = java.util.Set.copyOf(services);
        return new UpdatePlan(
                resolvedAt,
                seasonTag,
                seasonPrerelease,
                // Drops the resource pack, and keeps null away from Set.copyOf's contains(), which throws.
                changes.stream()
                        .filter(change -> change.service() != null && wanted.contains(change.service()))
                        .toList(),
                unclaimed.stream().filter(one -> wanted.contains(one.service())).toList(),
                notes);
    }

    /**
     * Returns the plan without some services, so a run never installs into a held service.
     *
     * @param services compose service names to leave out, empty returns the plan unchanged
     */
    public UpdatePlan withoutServices(final java.util.Collection<String> services) {
        if (services.isEmpty()) {
            return this;
        }
        final java.util.Set<String> gone = java.util.Set.copyOf(services);
        return new UpdatePlan(
                resolvedAt,
                seasonTag,
                seasonPrerelease,
                // Same null check as onlyServices, here keeping the resource pack: it belongs to no service.
                changes.stream()
                        .filter(change -> change.service() == null || !gone.contains(change.service()))
                        .toList(),
                unclaimed.stream().filter(one -> !gone.contains(one.service())).toList(),
                notes);
    }

    /** Returns whether anything is absent, not merely unknown. */
    public boolean hasMissing() {
        return changes.stream().anyMatch(change -> change.status() == Change.Status.MISSING);
    }
}
