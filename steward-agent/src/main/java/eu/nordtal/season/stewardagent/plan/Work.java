package eu.nordtal.season.stewardagent.plan;

import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.messages.MessageRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What an {@link UpdatePlan} moves per service, and what the sources found, as the resolve steps of a run write them.
 * The resource pack has no service, so what is found about it is the run's.
 *
 * @param services the artefacts each service moves, in the order of the plan; a service that moves none stops nothing
 * @param findings what failed or needs a look, each about one service or, without one, about the run
 */
public record Work(Map<String, List<RunStep.Change>> services, List<Finding> findings) {

    public Work {
        final Map<String, List<RunStep.Change>> copied = new LinkedHashMap<>();
        services.forEach((service, changes) -> copied.put(service, List.copyOf(changes)));
        services = java.util.Collections.unmodifiableMap(copied);
        findings = List.copyOf(findings);
    }

    /**
     * One thing a source found that a resolve step ends on.
     *
     * @param names what it is about, each written on the step by its name alone, such as a missing secret
     */
    public record Finding(
            @Nullable String service, RunStep.Outcome outcome, RunStep.Reason reason, List<String> names) {

        public Finding {
            names = List.copyOf(names);
        }

        public Finding(final @Nullable String service, final RunStep.Outcome outcome, final RunStep.Reason reason) {
            this(service, outcome, reason, List.of());
        }

        /** Returns the same finding for each service. */
        public static List<Finding> each(
                final Collection<String> services, final RunStep.Outcome outcome, final RunStep.Reason reason) {
            return services.stream()
                    .map(service -> new Finding(service, outcome, reason))
                    .toList();
        }
    }

    public static Work of(final UpdatePlan plan) {
        final Map<String, List<RunStep.Change>> work = new LinkedHashMap<>();
        final List<Finding> findings = new ArrayList<>();
        // The notes of the resolver come first.
        plan.notes().forEach(note -> findings.add(new Finding(null, RunStep.Outcome.WARNING, reason(note))));

        for (final Change change : plan.changes()) {
            if (change.service() == null) {
                if (change.status().isFailure()) {
                    findings.add(new Finding(null, RunStep.Outcome.FAILED, RunStep.Reason.SOURCE_FAILED));
                }
                continue;
            }
            final List<RunStep.Change> moving = work.computeIfAbsent(change.service(), key -> new ArrayList<>());
            if (change.status().isWork()) {
                // The run skips a service it cannot trust, so nothing of it moves.
                if (plan.blocker(change.service()) == null) {
                    moving.add(moving(change));
                }
            } else if (change.status() == Change.Status.NOT_IN_RELEASE) {
                // Our release has no file for this jar, and the installed one stays.
                findings.add(new Finding(change.service(), RunStep.Outcome.WARNING, RunStep.Reason.NOT_IN_RELEASE));
            } else if (change.status().isFailure()
                    && findings.stream()
                            .noneMatch(found -> change.service().equals(found.service())
                                    && found.outcome() == RunStep.Outcome.FAILED)) {
                // One unreadable row makes the whole service untrustworthy.
                findings.add(new Finding(change.service(), RunStep.Outcome.FAILED, RunStep.Reason.SOURCE_FAILED));
            }
        }

        for (final UpdatePlan.Unclaimed left : plan.unclaimed()) {
            // Loud rather than tidy: a claimless jar is usually a renamed plugin, loaded twice.
            findings.add(new Finding(left.service(), RunStep.Outcome.WARNING, RunStep.Reason.UNCLAIMED));
        }
        return new Work(work, findings);
    }

    /** Returns the services that move something, which a run stops. */
    public List<String> moving() {
        return services.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Returns whether anything is going to move. */
    public boolean isWork() {
        return !moving().isEmpty();
    }

    /** Returns what the service moves, none for a service the plan does not name. */
    public List<RunStep.Change> of(final String service) {
        return services.getOrDefault(service, List.of());
    }

    /** Returns the work with one more change for the service, after the ones it has. */
    public Work with(final String service, final RunStep.Change change) {
        final Map<String, List<RunStep.Change>> more = new LinkedHashMap<>(services);
        final List<RunStep.Change> changes = new ArrayList<>(of(service));
        changes.add(change);
        more.put(service, changes);
        return new Work(more, findings);
    }

    public Work with(final List<Finding> more) {
        final List<Finding> combined = new ArrayList<>(findings);
        combined.addAll(more);
        return new Work(services, combined);
    }

    /** Returns the work without those services. What was found about them stays. */
    public Work without(final Collection<String> gone) {
        final Map<String, List<RunStep.Change>> left = new LinkedHashMap<>(services);
        left.keySet().removeAll(gone);
        return new Work(left, findings);
    }

    /** Returns the findings about one service, or with {@code null} about the run. */
    public List<Finding> about(final @Nullable String service) {
        return findings.stream()
                .filter(found -> java.util.Objects.equals(found.service(), service))
                .toList();
    }

    /** Returns the reason of a note of the resolver. Any note but Velocity's failed its source. */
    private static RunStep.Reason reason(final MessageRef note) {
        return "report.velocity-ahead".equals(note.key())
                ? RunStep.Reason.VELOCITY_AHEAD
                : RunStep.Reason.SOURCE_FAILED;
    }

    /**
     * Returns the change of an artefact as a version jump. Where the pair does not split, it keeps the filename.
     */
    private static RunStep.Change moving(final Change change) {
        final String wantedFile =
                change.wanted() == null ? null : change.wanted().fileName();
        return VersionPair.of(change.installed(), wantedFile)
                .map(pair -> new RunStep.Change(change.artifact(), pair.from(), pair.to(), null))
                .orElseGet(() -> new RunStep.Change(
                        change.artifact(),
                        change.installed(),
                        change.wanted() == null ? null : change.wanted().version(),
                        null));
    }
}
