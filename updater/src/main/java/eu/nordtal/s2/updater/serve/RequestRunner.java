package eu.nordtal.s2.updater.serve;

import eu.nordtal.s2.common.update.UpdateRequest;

import org.jetbrains.annotations.NotNull;

/**
 * Carries out one claimed request.
 * <p>
 * An interface so that {@link UpdateServer}'s loop - the reconnects, the drain, the arithmetic that
 * decides when a countdown fires - can be tested without a network, a database or four Docker
 * volumes. {@link Runner} is the only implementation that does anything.
 * </p>
 */
@FunctionalInterface
public interface RequestRunner {

    /**
     * @return what happened. Never throws: the caller holds a row marked {@code RUNNING} that
     *         somebody is watching, and an exception escaping here would leave it open forever
     */
    @NotNull Outcome run(@NotNull UpdateRequest request);
}
