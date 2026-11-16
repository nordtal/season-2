package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Position;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/** The {@code boards} group places the boards at spawn. A save redraws them. */
@ConfigSpec
public interface BoardsSpec {

    @Order(20)
    @Name("Boards")
    @Key("boards")
    @Position(Position.Shape.SPOT)
    @NoExplanationNeeded
    default List<BoardSpec> boards() {
        return DefaultSmp.BOARDS;
    }
}
