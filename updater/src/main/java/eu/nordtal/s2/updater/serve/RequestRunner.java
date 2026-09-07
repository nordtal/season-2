package eu.nordtal.s2.updater.serve;

import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateRequest;

import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

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
     * @param request  the claimed row
     * @param progress called each time the run reaches a new stage, so that the surfaces watching
     *                 the row can redraw. An update now takes minutes - it waits for every service
     *                 it stopped to report healthy - and a message that does not change for five
     *                 minutes looks exactly like one that has hung
     * @return what happened. Never throws: the caller holds a row marked {@code RUNNING} that
     *         somebody is watching, and an exception escaping here would leave it open forever
     */
    @NotNull Outcome run(@NotNull UpdateRequest request, @NotNull Consumer<UpdateReport> progress);
}
