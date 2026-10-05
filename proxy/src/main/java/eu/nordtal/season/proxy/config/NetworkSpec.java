package eu.nordtal.season.proxy.config;

import eu.nordtal.season.spec.annotation.Comment;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.Order;

/**
 * The proxy's own place in the network: whether it is the standby, where players reach it, how often numbers are read.
 * The limit and the allowlist are the network's, in {@code eu.nordtal.season.settings.network}; the MOTD is a message.
 */
@ConfigSpec
public interface NetworkSpec {

    @Order(1)
    @Name("Snapshot refresh (seconds)")
    @Key("snapshot-refresh-seconds")
    @Comment({
        "How often the numbers the MOTD shows are re-read from the database.",
        "A ping never touches the database, and a failed refresh keeps the previous numbers."
    })
    @Explain("How often the MOTD's live numbers are refreshed from the database; a ping itself never touches it.")
    default int snapshotRefreshSeconds() {
        return 10;
    }

    @Order(2)
    @Name("Public address")
    @Key("public-address")
    @Comment({
        "How a client reaches this network from outside, host and port, as typed into",
        "Minecraft. Empty means this proxy never transfers anybody. Include the port, since a",
        "transfer resolves no SRV record: play.example.com:25565, not play.example.com."
    })
    @Explain(
            "Host and port a client reaches this network on from outside, as a transfer names it. Empty means no transfer is ever offered.")
    default String publicAddress() {
        return "";
    }

    @Order(3)
    @Name("Standby port")
    @Key("standby-port")
    @Comment({
        "The port the standby proxy is published on, on the same host as public-address.",
        "Set it through PROXY_STANDBY_PORT in .env, which moves both sides; changing it here",
        "only changes what players are told."
    })
    @Explain("The port the standby proxy is published on, the only thing that distinguishes it from this one.")
    default int standbyPort() {
        return 25566;
    }

    @Order(4)
    @Name("Standby")
    @Key("standby")
    @Comment({
        "Whether this process is the standby proxy. False everywhere except one service in",
        "compose.yml: a live proxy set to true would transfer every player to itself forever.",
        "A standby parks arrivals in server-limbo-standby, releases nobody, sends them back to",
        "public-address once it answers, and writes no player counts."
    })
    @Explain(
            "Whether this process is the standby proxy: false for the one players connect to, true in exactly one place in compose.yml.")
    default boolean standby() {
        return false;
    }
}
