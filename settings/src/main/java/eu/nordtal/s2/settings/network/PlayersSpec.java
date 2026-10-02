package eu.nordtal.s2.settings.network;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.Order;
import java.util.List;

/** How many players the network takes and what they may type, which the proxy enforces and every server reads. */
@ConfigSpec(header = "network: how many players the network takes, and what a player may type")
public interface PlayersSpec {

    @Order(1)
    @Name("Max players")
    @Key("max-players")
    @Comment({
        "How many players may be on the network at once: advertised, and enforced at login by",
        "the proxy alone. Admins are exempt. The servers behind it take whoever the proxy sends."
    })
    @Explain("How many players may be on the network at once; only the proxy enforces it, admins are exempt.")
    default int maxPlayers() {
        return 500;
    }

    @Order(2)
    @Name("Command allowlist")
    @Key("command-allowlist")
    @Comment({
        "Every command a player who is not an admin may type or see, anywhere on the network.",
        "Anything not listed is refused like a mistyped command. Admins are exempt.",
        "",
        "An entry is a path without the slash: 'hg ready', 'poi', 'msg'. Everything",
        "under an allowed path is allowed, and so is every path above one."
    })
    @Explain("Every command a non-admin may type anywhere on the network; anything not listed is refused.")
    default List<String> commandAllowlist() {
        // Ours, and only ours: every vanilla command, including /help, is deliberately absent.
        return List.of("navigate", "poi", "hg ready", "msg", "whisper", "r", "discord", "rules");
    }
}
