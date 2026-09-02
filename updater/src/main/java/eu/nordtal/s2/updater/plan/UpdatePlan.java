package eu.nordtal.s2.updater.plan;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * The whole answer to "what would a run do", and nothing more: <b>step 1 of docs/updater.md writes
 * nothing.</b>
 *
 * <p>Keeping the plan as a value, separate from anything that acts on it, is what makes the
 * dangerous half testable. Every trap in the sources - a {@code -sources.jar}, a pre-release, an
 * unmounted volume, a renamed asset - is a property of this object and is asserted against
 * recorded API responses, without a container, a network or a server.</p>
 *
 * @param seasonTag        the release tag that was actually resolved. Printed even when nothing
 *                         changed, because "latest" resolving to last week's tag is what a
 *                         forgotten draft release looks like from in here.
 * @param seasonPrerelease true only when an operator pinned a pre-release by tag;
 *                         {@code /releases/latest} never returns one.
 * @param unclaimed        jars found in a {@code plugins/} folder that no row in this plan
 *                         accounts for. Never touched, always reported: an unclaimed jar is either
 *                         something installed by hand - which is fine and should be visible - or
 *                         the same plugin under a name that has changed, which is the one way this
 *                         module can end up installing a second copy of something.
 */
public record UpdatePlan(@NotNull Instant resolvedAt,
                         @Nullable String seasonTag,
                         boolean seasonPrerelease,
                         @NotNull List<Change> changes,
                         @NotNull List<Unclaimed> unclaimed) {

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
     * The same plan with everything but {@link Change.Status#MISSING} dropped - what a bootstrap
     * is allowed to install.
     *
     * <p><b>Why MISSING and not {@code isWork()}.</b> {@code OUTDATED} is a version move, and this
     * module's first rule is that a container coming back up comes back on exactly the jars it was
     * running. Filtering here rather than in the caller is what makes that rule a property of the
     * plan instead of a promise in a comment: a bootstrap literally cannot express "upgrade", so
     * a crash restart at three in the morning has nothing to move. A volume that already has a jar
     * for an artefact keeps it, however old it is; only an artefact with nothing installed at all
     * is fetched.</p>
     *
     * <p>{@code unclaimed} is carried over untouched. It describes files nobody claimed, an apply
     * never acts on it, and a bootstrap dropping it would make the report it prints disagree with
     * the one {@code updater apply} prints for the same volumes.</p>
     */
    public @NotNull UpdatePlan onlyMissing() {
        return new UpdatePlan(resolvedAt, seasonTag, seasonPrerelease,
                withStatus(Change.Status.MISSING), unclaimed);
    }
}
