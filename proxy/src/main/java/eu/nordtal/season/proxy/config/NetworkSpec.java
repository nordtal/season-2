package eu.nordtal.season.proxy.config;

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
    @Explain("How often the MOTD's live numbers are refreshed from the database; a ping itself never touches it.")
    default int snapshotRefreshSeconds() {
        return 10;
    }

    @Order(2)
    @Name("Public address")
    @Key("public-address")
    @Explain(
            "Host and port a client reaches this network on from outside, as a transfer names it. Empty means no transfer is ever offered.")
    default String publicAddress() {
        return "";
    }

    @Order(3)
    @Name("Standby port")
    @Key("standby-port")
    @Explain("The port the standby proxy is published on, the only thing that distinguishes it from this one.")
    default int standbyPort() {
        return 25566;
    }

    @Order(4)
    @Name("Standby")
    @Key("standby")
    @Explain(
            "Whether this process is the standby proxy: false for the one players connect to, true in exactly one place in compose.yml.")
    default boolean standby() {
        return false;
    }
}
