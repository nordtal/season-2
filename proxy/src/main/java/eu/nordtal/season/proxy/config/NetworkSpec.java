package eu.nordtal.season.proxy.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/**
 * The proxy's own place in the network: whether it is the standby, where players reach it.
 * The limit and the allowlist are the network's, in {@code eu.nordtal.season.settings.network}; the MOTD is a message.
 */
@ConfigSpec
public interface NetworkSpec {

    /** The address a client reaches this network on, as a transfer names it. */
    @Order(2)
    @Name("Public address")
    @Key("public-address")
    @Explain("As host:port. Empty means no transfer is ever offered.")
    default String publicAddress() {
        return "";
    }

    /** The port the standby proxy is published on, the only thing that distinguishes it from this one. */
    @Order(3)
    @Name("Standby port")
    @Key("standby-port")
    @NoExplanationNeeded
    default int standbyPort() {
        return 25566;
    }

    /** Where the live proxy itself listens, which the standby knocks on to know when to send players back. */
    @Order(5)
    @Name("Live proxy address")
    @Key("live-address")
    @Explain("As host:port, inside the network. The public address cannot tell: Caddy answers it for the standby.")
    default String liveAddress() {
        return "proxy:25565";
    }

    /** False for the proxy players connect to, true in exactly one place in compose.yml. */
    @Order(4)
    @Name("Standby")
    @Key("standby")
    @NoExplanationNeeded
    default boolean standby() {
        return false;
    }
}
