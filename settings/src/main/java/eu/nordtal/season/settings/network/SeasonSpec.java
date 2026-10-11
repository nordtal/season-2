package eu.nordtal.season.settings.network;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** The season the network runs, which every message can name as {@code {season.number}} and {@code {season.name}}. */
@ConfigSpec
public interface SeasonSpec {

    /** The tab list prints it in each player's language. */
    @Order(1)
    @Name("Number")
    @Key("number")
    @NoExplanationNeeded
    default int number() {
        return 2;
    }

    /** The season's name as the server browser shows it. */
    @Order(2)
    @Name("Name")
    @Key("name")
    @NoExplanationNeeded
    default String name() {
        return "Season 2";
    }
}
