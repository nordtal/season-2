package eu.nordtal.season.stewardagent.run;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReport.Note;
import eu.nordtal.season.database.update.UpdateReport.Step;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.stewardagent.apply.ApplyResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Writes the result of an apply into a report, for an update run and for the start-up install. */
final class Installed {

    private Installed() {}

    static UpdateReport into(final UpdateReport before, final ApplyResult result, final Collection<String> services) {
        UpdateReport report = before;
        for (final ApplyResult.Outcome outcome : result.outcomes()) {
            // A file of no service, the resource pack, has no line to fail on.
            if (outcome.service() == null && didNotGoIn(outcome)) {
                report = report.withNote(Note.failed(Step.INSTALL, notInstalled(outcome)));
            }
        }
        for (final String service : services) {
            // Only a successful apply marks the service INSTALLED.
            final UpdateReport.Facts failure = failureFor(result, service);
            report = report.with(
                    failure == null
                            ? report.line(service).at(UpdateReport.State.INSTALLED)
                            : report.line(service).failed(failure));
        }
        return report;
    }

    /**
     * Writes one install step per service the apply touched and one for the pack.
     *
     * Each step names the artefacts with the versions the plan moved them from and to.
     */
    static void record(
            final Steps record,
            final UpdateReport planned,
            final ApplyResult result,
            final Collection<String> services) {
        final Map<@Nullable String, List<ApplyResult.Outcome>> bySource = new LinkedHashMap<>();
        for (final ApplyResult.Outcome outcome : result.outcomes()) {
            if (outcome.service() == null || services.contains(outcome.service())) {
                bySource.computeIfAbsent(outcome.service(), service -> new ArrayList<>())
                        .add(outcome);
            }
        }
        bySource.forEach((service, outcomes) -> {
            final Steps.Step step = record.begin(RunStep.Kind.INSTALL, service);
            for (final ApplyResult.Outcome outcome : outcomes) {
                final UpdateReport.Change moved = service == null ? null : moving(planned, service, outcome.artifact());
                step.change(
                        outcome.artifact(),
                        moved == null ? null : moved.from(),
                        moved == null || UpdateReport.Change.NONE.equals(moved.to()) ? null : moved.to(),
                        null);
            }
            if (outcomes.stream().anyMatch(Installed::didNotGoIn)) {
                step.failed(RunStep.Reason.TOOL_FAILED);
            } else {
                step.done();
            }
        });
    }

    private static UpdateReport.@Nullable Change moving(
            final UpdateReport planned, final String service, final String artefact) {
        return planned.services().stream()
                .filter(line -> line.service().equals(service))
                .flatMap(line -> line.changes().stream())
                .filter(change -> change.state() == UpdateReport.Change.State.MOVING)
                .filter(change -> change.artefact().equals(artefact))
                .findFirst()
                .orElse(null);
    }

    /** Why one service's install did not happen, or {@code null} when it did, counting {@code SKIPPED} as failed. */
    private static UpdateReport.@Nullable Facts failureFor(final ApplyResult result, final String service) {
        return result.outcomes().stream()
                .filter(outcome -> service.equals(outcome.service()))
                .filter(Installed::didNotGoIn)
                .findFirst()
                .map(outcome -> UpdateReport.Facts.notInstalled(outcome.artifact(), whyNotInstalled(outcome)))
                .orElse(null);
    }

    private static boolean didNotGoIn(final ApplyResult.Outcome outcome) {
        return outcome.status() == ApplyResult.Status.FAILED || outcome.status() == ApplyResult.Status.SKIPPED;
    }

    private static MessageRef notInstalled(final ApplyResult.Outcome outcome) {
        return TEXTS.report().notInstalled(outcome.artifact(), whyNotInstalled(outcome));
    }

    private static String whyNotInstalled(final ApplyResult.Outcome outcome) {
        final String detail = outcome.detail();
        return detail == null ? outcome.status().name().toLowerCase(Locale.ROOT) : detail;
    }
}
