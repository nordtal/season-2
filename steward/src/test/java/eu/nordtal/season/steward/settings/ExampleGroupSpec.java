package eu.nordtal.season.steward.settings;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Secret;
import java.util.List;

/** A group with a number, a text, a list of items and a secret, as the settings form tests draw it. */
@ConfigSpec
public interface ExampleGroupSpec {

    @Key("max-players")
    default int maxPlayers() {
        return 20;
    }

    @Key("motd")
    default String motd() {
        return "Nordtal";
    }

    @Key("allowlist")
    default List<String> allowlist() {
        return List.of("msg");
    }

    @Key("prizes")
    @Refers(Refers.To.ITEM)
    default List<String> prizes() {
        return List.of("minecraft:diamond");
    }

    @Key("token")
    @Secret
    default String token() {
        return "";
    }
}
