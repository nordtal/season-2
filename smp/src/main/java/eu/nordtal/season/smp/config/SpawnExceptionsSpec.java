package eu.nordtal.season.smp.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/** The {@code spawn-exceptions} group holds doors and trapdoors that players may open inside spawn. */
@ConfigSpec
public interface SpawnExceptionsSpec {

    @Order(1)
    @Name("Doors and trapdoors")
    @Key("entries")
    @NoExplanationNeeded
    default List<SpawnExceptionSpec> entries() {
        return List.of();
    }
}
