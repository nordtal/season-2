package eu.nordtal.season.stewardagent.run;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReport.Note;
import eu.nordtal.season.database.update.UpdateReport.Step;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.stewardagent.apply.ApplyResult;
import java.util.Collection;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** How an apply went, written into a report; an update run and the start-up install both write theirs this way. */
final class Installed {

    private Installed() {}

    /** The report with each service's line installed or failed, and a failed record for a pack that did not go in. */
    static UpdateReport into(final UpdateReport before, final ApplyResult result, final Collection<String> services) {
        UpdateReport report = before;
        for (final ApplyResult.Outcome outcome : result.outcomes()) {
            // A file of no service, the resource pack, has no line to fail on.
            if (outcome.service() == null && didNotGoIn(outcome)) {
                report = report.withNote(Note.failed(Step.INSTALL, notInstalled(outcome)));
            }
        }
        for (final String service : services) {
            // Only where the apply succeeded; marking every stopped service INSTALLED here would be premature.
            final MessageRef failure = failureFor(result, service);
            report = report.with(
                    failure == null
                            ? report.line(service).at(UpdateReport.State.INSTALLED)
                            : report.line(service).failed(failure));
        }
        return report;
    }

    /** Why one service's install did not happen, or {@code null} when it did, counting {@code SKIPPED} as failed. */
    private static @Nullable MessageRef failureFor(final ApplyResult result, final String service) {
        return result.outcomes().stream()
                .filter(outcome -> service.equals(outcome.service()))
                .filter(Installed::didNotGoIn)
                .findFirst()
                .map(Installed::notInstalled)
                .orElse(null);
    }

    private static boolean didNotGoIn(final ApplyResult.Outcome outcome) {
        return outcome.status() == ApplyResult.Status.FAILED || outcome.status() == ApplyResult.Status.SKIPPED;
    }

    /** An artefact that did not go in, with the applier's reason or its status word when it gave none. */
    private static MessageRef notInstalled(final ApplyResult.Outcome outcome) {
        return TEXTS.report()
                .notInstalled(
                        outcome.artifact(),
                        outcome.detail() == null ? outcome.status().name().toLowerCase(Locale.ROOT) : outcome.detail());
    }
}
