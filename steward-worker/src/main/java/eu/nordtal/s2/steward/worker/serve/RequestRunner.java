package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateRequest;
import java.util.function.Consumer;

/** Carries out one claimed request, an interface so {@link UpdateServer}'s loop can be tested without a network. */
@FunctionalInterface
public interface RequestRunner {

    /**
     * Runs the request.
     *
     * @param request the claimed row
     * @param progress called at each new stage, so the surfaces watching the row can redraw
     * @return what happened; never throws, since the caller holds a {@code RUNNING} row somebody watches
     */
    Outcome run(UpdateRequest request, Consumer<UpdateReport> progress);
}
