package eu.nordtal.season.database.update;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A run as its rows hold it: the stage on its inbox row and its steps with their facts.
 *
 * @param stage {@code null} before a statement wrote one
 */
public record RunRecord(UpdateReport.@Nullable Stage stage, List<RunStep.Row> steps) {

    public static final RunRecord EMPTY = new RunRecord(null, List.of());
}
