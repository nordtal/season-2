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
 * @param retiring the files of {@link Retired} plugins still in a {@code plugins/} folder, which a run deletes
 */
public record UpdatePlan(
        Instant resolvedAt,
        @Nullable String seasonTag,
        boolean seasonPrerelease,
        List<Change> changes,
        List<Unclaimed> unclaimed,
        List<MessageRef> notes,
        List<Retiring> retiring) {

    public record Unclaimed(String service, String fileName) {}

    /**
     * A retired plugin's files on one service.
     *
     * @param files names in {@code plugins/}: its jars and its data folder
     */
    public record Retiring(String service, String artifact, List<String> files) {

        public Retiring {
            files = List.copyOf(files);
        }
    }

    /** A plan with no retired plugin to delete. */
    public UpdatePlan(
            final Instant resolvedAt,
            final @Nullable String seasonTag,
            final boolean seasonPrerelease,
            final List<Change> changes,
            final List<Unclaimed> unclaimed,
            final List<MessageRef> notes) {
        this(resolvedAt, seasonTag, seasonPrerelease, changes, unclaimed, notes, List.of());
    }

    /**
     * Returns the row that makes a run leave {@code service} alone, or {@code null} when the run applies its work.
     *
     * Any failure but the server jar blocks it; the applier, report and plugin list all read this.
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
                .findFirst()
                .orElse(null);
    }

    public boolean hasWork() {
        return !retiring.isEmpty()
                || changes.stream().anyMatch(change -> change.status().isWork());
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
     * A bootstrap never upgrades or deletes. Unresolved rows stay, so an outage empties the folder visibly.
     */
    public UpdatePlan onlyMissing() {
        final List<Change> keep = changes.stream()
                .filter(change -> change.status() == Change.Status.MISSING
                        || change.status().isFailure())
                .toList();
        return new UpdatePlan(resolvedAt, seasonTag, seasonPrerelease, keep, unclaimed, notes);
    }

    /**
     * Returns the plan for some of the services, without a row that belongs to no service.
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
                // Drops a row without a service, and keeps null away from Set.copyOf's contains(), which throws.
                changes.stream()
                        .filter(change -> change.service() != null && wanted.contains(change.service()))
                        .toList(),
                unclaimed.stream().filter(one -> wanted.contains(one.service())).toList(),
                notes,
                retiring.stream().filter(one -> wanted.contains(one.service())).toList());
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
                // Same null check as onlyServices, here keeping a row that belongs to no service.
                changes.stream()
                        .filter(change -> change.service() == null || !gone.contains(change.service()))
                        .toList(),
                unclaimed.stream().filter(one -> !gone.contains(one.service())).toList(),
                notes,
                retiring.stream().filter(one -> !gone.contains(one.service())).toList());
    }

    /** Returns whether anything is absent, not merely unknown. */
    public boolean hasMissing() {
        return changes.stream().anyMatch(change -> change.status() == Change.Status.MISSING);
    }
}
