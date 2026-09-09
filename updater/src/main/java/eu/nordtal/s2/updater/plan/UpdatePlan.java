package eu.nordtal.s2.updater.plan;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * The whole answer to "what would a run do", and nothing more: resolving writes nothing.
 *
 * <p>Keeping the plan as a value, separate from anything that acts on it, is what makes the
 * dangerous half testable: every trap in the sources - a {@code -sources.jar}, a pre-release, an
 * unmounted volume, a renamed asset - is a property of this object and is asserted against recorded
 * API responses, without a container, a network or a server.</p>
 *
 * @param seasonTag        the release tag that was actually resolved. Printed even when nothing
 *                         changed, because "latest" resolving to last week's tag is what a
 *                         forgotten draft release looks like from in here.
 * @param seasonPrerelease true only when an operator pinned a pre-release by tag;
 *                         {@code /releases/latest} never returns one.
 * @param unclaimed        jars in a {@code plugins/} folder that no row accounts for. Never
 *                         touched, always reported: an unclaimed jar is either installed by hand or
 *                         the same plugin under a changed name, which is the one way this module
 *                         can end up installing a second copy of something.
 * @param notes            things the resolve worked out that belong to no single service or
 *                         artefact. Decided here and only drawn by {@link PlanReport}, so that no
 *                         surface composes a second opinion of its own.
 */
public record UpdatePlan(@NotNull Instant resolvedAt,
                         @Nullable String seasonTag,
                         boolean seasonPrerelease,
                         @NotNull List<Change> changes,
                         @NotNull List<Unclaimed> unclaimed,
                         @NotNull List<String> notes) {

    public record Unclaimed(@NotNull String service, @NotNull String fileName) {
    }

    /** Whether a run would move anything at all. */
    public boolean hasWork() {
        return changes.stream().anyMatch(change -> change.status().isWork());
    }

    /** Whether some part of the picture is missing, which makes "nothing to do" unsafe to believe. */
    public boolean hasFailures() {
        return changes.stream().anyMatch(change -> change.status().isFailure());
    }

    public @NotNull List<Change> withStatus(final Change.@NotNull Status status) {
        return changes.stream().filter(change -> change.status() == status).toList();
    }

    /**
     * The same plan reduced to what a bootstrap may install - {@link Change.Status#MISSING} - plus
     * every row that could not be resolved at all.
     *
     * <p>MISSING and not {@code isWork()}, because a container coming back up must come back on
     * exactly the jars it was running: a bootstrap cannot express "upgrade", so a crash restart at
     * three in the morning has nothing to move. Filtering here makes that a property of the plan
     * rather than a promise in a caller.</p>
     *
     * <p>The unresolved rows stay. Dropping them turns a half-finished bootstrap into a report of
     * unbroken success - an API outage would leave a service with no season jar on it and nothing
     * saying so. Kept, the existing all-or-nothing rule empties that service's folder and the
     * entrypoint's empty-plugins guard catches it.</p>
     *
     * <p>{@code unclaimed} is carried over untouched, so a bootstrap's report agrees with an
     * apply's for the same volumes.</p>
     */
    public @NotNull UpdatePlan onlyMissing() {
        final List<Change> keep = changes.stream()
                .filter(change -> change.status() == Change.Status.MISSING
                        || change.status().isFailure())
                .toList();
        return new UpdatePlan(resolvedAt, seasonTag, seasonPrerelease, keep, unclaimed, notes);
    }

    /**
     * Whether anything here is actually absent, as opposed to merely unknown: a plan carrying
     * nothing but unresolved rows has no work in it, and a bootstrap must not announce one.
     */
    public boolean hasMissing() {
        return changes.stream().anyMatch(change -> change.status() == Change.Status.MISSING);
    }
}
