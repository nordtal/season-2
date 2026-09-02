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
     * The same plan reduced to what a bootstrap may install - {@link Change.Status#MISSING} - plus
     * every row that could not be resolved at all.
     *
     * <p><b>Why MISSING and not {@code isWork()}.</b> {@code OUTDATED} is a version move, and this
     * module's first rule is that a container coming back up comes back on exactly the jars it was
     * running. Filtering here rather than in the caller is what makes that rule a property of the
     * plan instead of a promise in a comment: a bootstrap literally cannot express "upgrade", so
     * a crash restart at three in the morning has nothing to move. A volume that already has a jar
     * for an artefact keeps it, however old it is; only an artefact with nothing installed at all
     * is fetched.</p>
     *
     * <p><b>Why the UNRESOLVED rows stay, since 2026-09-02.</b> They used to be dropped, and that
     * turned a half-finished bootstrap into a report of unbroken success. On the first real
     * deployment the GitHub releases API answered 403: eight artefacts went unresolved, the filter
     * removed all eight before anything was rendered, and what was left was the six rows that had
     * answered - closed with <em>"Everything asked for was done."</em> Not one season jar had been
     * installed. Three servers were caught by the entrypoint's empty-plugins guard; {@code smp} was
     * not, because PacketEvents and Chunky <em>had</em> resolved, so its folder was not empty and it
     * came up with no season on it.
     *
     * <p>Keeping the rows needs no new machinery, which is the point: {@code Applier.applyService}
     * already skips a whole service when any row of it is a failure, so the same outage now leaves
     * {@code smp}'s folder empty and the guard catches it, and {@code Report} already has the
     * footer that says nothing was installed and not because everything was current. It is the
     * module's own rule - <b>"skipped" is a third answer, not a quiet kind of "fine"</b> - applied
     * to the one path that had lost it.</p>
     *
     * <p>{@code unclaimed} is carried over untouched. It describes files nobody claimed, an apply
     * never acts on it, and a bootstrap dropping it would make the report it prints disagree with
     * the one {@code updater apply} prints for the same volumes.</p>
     */
    public @NotNull UpdatePlan onlyMissing() {
        final List<Change> keep = changes.stream()
                .filter(change -> change.status() == Change.Status.MISSING
                        || change.status().isFailure())
                .toList();
        return new UpdatePlan(resolvedAt, seasonTag, seasonPrerelease, keep, unclaimed);
    }

    /**
     * Whether anything here is actually absent - as opposed to merely unknown.
     * <p>
     * The distinction {@link #onlyMissing()} exists to preserve: a plan carrying nothing but
     * UNRESOLVED rows has no work in it, and a bootstrap must not announce one.
     * </p>
     */
    public boolean hasMissing() {
        return changes.stream().anyMatch(change -> change.status() == Change.Status.MISSING);
    }
}
