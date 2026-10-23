package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.UpdateRequest;
import java.util.function.Consumer;

/** Carries out one claimed request. */
@FunctionalInterface
public interface RequestRunner {

    /**
     * Runs the request.
     *
     * @param request the claimed row
     * @param progress called at each new stage
     * @return what happened
     */
    Outcome run(UpdateRequest request, Consumer<RunStage> progress);
}
