package eu.nordtal.season.settings.network;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.Order;

/** The season the network runs, which every message can name as {@code {season.number}} and {@code {season.name}}. */
@ConfigSpec
public interface SeasonSpec {

    @Order(1)
    @Name("Number")
    @Key("number")
    @Explain("The season's number, which the tab list prints in each player's language.")
    default int number() {
        return 2;
    }

    @Order(2)
    @Name("Name")
    @Key("name")
    @Explain("The season's name as the server browser shows it.")
    default String name() {
        return "Season 2";
    }
}
