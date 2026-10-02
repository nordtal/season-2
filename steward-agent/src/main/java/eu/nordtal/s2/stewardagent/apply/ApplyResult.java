package eu.nordtal.s2.stewardagent.apply;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** What a run actually did, one line per artefact. */
public record ApplyResult(List<Outcome> outcomes) {

    public enum Status {
        /** A file was fetched and moved into place, or the pack's two lines were rewritten. */
        DONE,
        /** Already what it should be; nothing was fetched and nothing was written. */
        UNCHANGED,
        /** Deliberately not attempted, because another artefact of the same server could not be resolved. */
        SKIPPED,
        /** There is no file for this artefact on this Minecraft version, so there was nothing to attempt. */
        UNSUPPORTED,
        /** Attempted and failed; nothing of that server was moved. */
        FAILED
    }

    public record Outcome(
            @Nullable String service,
            String artifact,
            Status status,
            @Nullable String detail) {}

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

    /** Whether a restart would be safe to offer: not when anything failed. */
    public boolean restartWorthOffering() {
        return changedAnything() && !hasFailures();
    }
}
