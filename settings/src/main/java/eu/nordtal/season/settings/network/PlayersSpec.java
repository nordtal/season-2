package eu.nordtal.season.settings.network;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

@ConfigSpec
public interface PlayersSpec {

    /** Only the proxy enforces it. */
    @Order(1)
    @Name("Max players")
    @Key("max-players")
    @NoExplanationNeeded
    default int maxPlayers() {
        return 500;
    }

    @Order(2)
    @Name("Command allowlist")
    @Key("command-allowlist")
    @NoExplanationNeeded
    default List<String> commandAllowlist() {
        // Only commands of this network. Every vanilla command, including /help, is absent.
        return List.of("navigate", "poi", "hg ready", "msg", "whisper", "r", "discord", "rules");
    }
}
