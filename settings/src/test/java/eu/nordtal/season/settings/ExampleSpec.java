package eu.nordtal.season.settings;

import eu.nordtal.season.spec.Specs;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Secret;
import java.util.List;

/** A group with a number, a text, a secret and a nested list, as the tests load it. */
@ConfigSpec
public interface ExampleSpec {

    @Key("view-distance")
    default int viewDistance() {
        return 0;
    }

    @Key("name")
    default String name() {
        return "nordtal";
    }

    @Key("token")
    @Secret
    default String token() {
        return "";
    }

    @Key("nested")
    default Nested nested() {
        return Specs.createDefault(Nested.class);
    }

    /** A nested group of the example. */
    @ConfigSpec
    interface Nested {

        @Key("words")
        default List<String> words() {
            return List.of("a");
        }
    }
}
