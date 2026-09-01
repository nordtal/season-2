package eu.nordtal.s2.updater.serve;

import eu.nordtal.s2.common.update.UpdateStatus;

import org.jetbrains.annotations.NotNull;

/**
 * What running one request came to: the status to write back, and the text to write with it.
 *
 * @param status {@link UpdateStatus#DONE} or {@link UpdateStatus#FAILED}
 * @param report what a person reads afterwards - in a Discord embed, in a chat line, or in the
 *               table. Always the updater's own rendering, never a second one, so every surface
 *               shows the same words {@code updater apply} prints on the host
 */
public record Outcome(@NotNull UpdateStatus status, @NotNull String report) {

    static Outcome done(final @NotNull String report) {
        return new Outcome(UpdateStatus.DONE, report);
    }

    static Outcome failed(final @NotNull String report) {
        return new Outcome(UpdateStatus.FAILED, report);
    }
}
