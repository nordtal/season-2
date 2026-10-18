package eu.nordtal.season.stewardagent.apply;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** What a run did, one line per artefact. */
public record ApplyResult(List<Outcome> outcomes) {

    public enum Status {
        /** A file was fetched and moved into place, or the pack's two lines were rewritten. */
        DONE,
        /** The state was already right. Nothing was fetched or written. */
        UNCHANGED,
        /** Not attempted, because another artefact of the same server could not be resolved. */
        SKIPPED,
        /** No file exists for this artefact on this Minecraft version. */
        UNSUPPORTED,
        /** Attempted and failed; nothing of that server was moved. */
        FAILED
    }

    public record Outcome(
            @Nullable String service,
            String artifact,
            Status status,
            @Nullable String detail,
            @Nullable String file) {

        public Outcome(
                final @Nullable String service,
                final String artifact,
                final Status status,
                final @Nullable String detail) {
            this(service, artifact, status, detail, null);
        }
    }

    public boolean changedAnything() {
        return outcomes.stream().anyMatch(outcome -> outcome.status() == Status.DONE);
    }

    public boolean hasFailures() {
        return outcomes.stream().anyMatch(outcome -> outcome.status() == Status.FAILED);
    }

    /**
     * Whether anything was deliberately not attempted.
     *
     * A run that skipped every server has no failure and nothing to do, and must not read as current.
     */
    public boolean skippedAnything() {
        return outcomes.stream().anyMatch(outcome -> outcome.status() == Status.SKIPPED);
    }

    public boolean restartWorthOffering() {
        return changedAnything() && !hasFailures();
    }
}
