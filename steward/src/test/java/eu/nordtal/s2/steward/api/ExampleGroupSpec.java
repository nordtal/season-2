package eu.nordtal.s2.steward.api;

import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Secret;
import java.util.List;

/** A group with a number, a text, a list and a secret, as the settings form tests draw it. */
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

    @Key("token")
    @Secret
    default String token() {
        return "";
    }
}
