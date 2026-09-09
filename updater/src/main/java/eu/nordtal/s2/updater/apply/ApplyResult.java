package eu.nordtal.s2.updater.apply;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** What a run actually did, one line per artefact. */
public record ApplyResult(@NotNull List<Outcome> outcomes) {

    public enum Status {
        /** A file was fetched and moved into place, or the pack's two lines were rewritten. */
        DONE,
        /** Already what it should be; nothing was fetched and nothing was written. */
        UNCHANGED,
        /**
         * Deliberately not attempted. A whole server is skipped when any one of its artefacts could
         * not be resolved: its plugins move together or not at all.
         */
        SKIPPED,
        /**
         * There is no file for this artefact on this Minecraft version, so there was nothing to
         * attempt. Its own word because {@link #UNCHANGED} claims something is installed and
         * {@link #SKIPPED} is the whole-service refusal.
         */
        UNSUPPORTED,
        /** Attempted and failed. Nothing of that server was moved - see {@link Applier}. */
        FAILED
    }

    public record Outcome(@Nullable String service,
                          @NotNull String artifact,
                          @NotNull Status status,
                          @Nullable String detail) {
    }

    public boolean changedAnything() {
        return outcomes.stream().anyMatch(outcome -> outcome.status() == Status.DONE);
    }

    public boolean hasFailures() {
        return outcomes.stream().anyMatch(outcome -> outcome.status() == Status.FAILED);
    }

    /**
     * Whether anything was deliberately not attempted. Reported separately from a failure and from
     * "nothing to do": a run that skipped every server has neither, and must not read as current.
     */
    public boolean skippedAnything() {
        return outcomes.stream().anyMatch(outcome -> outcome.status() == Status.SKIPPED);
    }

    /**
     * Whether a restart would be safe to offer. Not if anything failed: reporting happens before
     * restarting so that a person sees a half-done run before the network goes down on it.
     */
    public boolean restartWorthOffering() {
        return changedAnything() && !hasFailures();
    }
}
