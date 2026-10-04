package eu.nordtal.s2.stewardagent.plan;

import eu.nordtal.s2.internalapi.agent.Topology;
import eu.nordtal.s2.messages.MessageRef;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a run would do, as a value that resolving produces without writing anything.
 *
 * @param seasonTag the release tag actually resolved, printed even when nothing changed
 * @param seasonPrerelease true only when an operator pinned a pre-release by tag
 * @param unclaimed jars in a {@code plugins/} folder no row accounts for, never touched and always reported
 * @param notes findings that belong to no single service, drawn only by {@link PlanReport}
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
     * The row that makes a run leave {@code service} alone entirely, or {@code null} when a run would apply its work.
     *
     * Any failure but the server jar or the pack blocks it; the applier, report and plugin list all read this.
     */
    public @Nullable Change blocker(final String service) {
        return blocker(changes.stream()
                .filter(change -> service.equals(change.service()))
                .toList());
    }

    /** {@link #blocker(String)} for one service's rows. */
    public static @Nullable Change blocker(final Collection<Change> serviceChanges) {
        return serviceChanges.stream()
                .filter(change -> change.status().isFailure())
                .filter(change -> !Topology.PAPER.equals(change.artifact()))
                .filter(change -> !Topology.VELOCITY.equals(change.artifact()))
                .filter(change -> !Topology.RESOURCE_PACK.equals(change.artifact()))
                .findFirst()
                .orElse(null);
    }

    /** Whether a run would move anything at all. */
    public boolean hasWork() {
        return changes.stream().anyMatch(change -> change.status().isWork());
    }

    /** Whether some part of the picture is missing, which makes "nothing to do" unsafe to believe. */
    public boolean hasFailures() {
        return changes.stream().anyMatch(change -> change.status().isFailure());
    }

    public List<Change> withStatus(final Change.Status status) {
        return changes.stream().filter(change -> change.status() == status).toList();
    }

    /**
     * The same plan reduced to what a bootstrap may install: {@link Change.Status#MISSING} and every unresolved row.
     *
     * A bootstrap never upgrades, and unresolved rows stay so an outage empties the folder rather than hiding.
     */
    public UpdatePlan onlyMissing() {
        final List<Change> keep = changes.stream()
                .filter(change -> change.status() == Change.Status.MISSING
                        || change.status().isFailure())
                .toList();
        return new UpdatePlan(resolvedAt, seasonTag, seasonPrerelease, keep, unclaimed, notes);
    }

    /**
     * The same plan narrowed to some of the services, without the resource pack.
     *
     * @param services compose service names; empty hands the plan back untouched, since empty is the whole network
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
     * The same plan with some services taken out of it, so a run never installs into a held service.
     *
     * @param services compose service names to leave out; empty hands the plan back untouched
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

    /** Whether anything here is actually absent, as opposed to merely unknown. */
    public boolean hasMissing() {
        return changes.stream().anyMatch(change -> change.status() == Change.Status.MISSING);
    }
}
