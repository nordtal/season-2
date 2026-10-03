package eu.nordtal.s2.settings.network;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.Order;

/** The season the network runs, which every message can name as {@code {season.number}} and {@code {season.name}}. */
@ConfigSpec
public interface SeasonSpec {

    @Order(1)
    @Name("Number")
    @Key("number")
    @Comment("The season's number, which the tab list prints in each player's own language.")
    @Explain("The season's number, which the tab list prints in each player's language.")
    default int number() {
        return 2;
    }

    @Order(2)
    @Name("Name")
    @Key("name")
    @Comment("The season's name as every message shows it in {season.name}, the MOTD included.")
    @Explain("The season's name as the server browser shows it.")
    default String name() {
        return "Season 2";
    }
}
