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
         * Deliberately not attempted. The whole of a server is skipped when any one of its
         * artefacts could not be resolved: a server's plugins move together or not at all, because
         * "the new SMP jar with last week's PacketEvents" is a combination nobody chose and nobody
         * tested.
         */
        SKIPPED,
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
     * Whether a restart would be safe to offer. It would not be if anything failed: the point of
     * reporting before restarting (step 5 of docs/updater.md) is that a person sees a half-done
     * run before the network goes down on it.
     */
    public boolean restartWorthOffering() {
        return changedAnything() && !hasFailures();
    }
}
