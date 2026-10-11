package eu.nordtal.season.settings.network;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/** How many players the network takes and what they may type, which the proxy enforces and every server reads. */
@ConfigSpec
public interface PlayersSpec {

    /** Only the proxy enforces it. */
    @Order(1)
    @Name("Max players")
    @Key("max-players")
    @Explain("Admins are exempt.")
    default int maxPlayers() {
        return 500;
    }

    /** Every command a non-admin may type anywhere on the network. */
    @Order(2)
    @Name("Command allowlist")
    @Key("command-allowlist")
    @Explain("Anything not listed is refused to a non-admin.")
    default List<String> commandAllowlist() {
        // Ours, and only ours: every vanilla command, including /help, is deliberately absent.
        return List.of("navigate", "poi", "hg ready", "msg", "whisper", "r", "discord", "rules");
    }
}
