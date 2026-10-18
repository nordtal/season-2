package eu.nordtal.season.proxy.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** The address of the proxy and whether it is the standby. */
@ConfigSpec
public interface NetworkSpec {

    /** The address that a transfer names. */
    @Order(2)
    @Name("Public address")
    @Key("public-address")
    @Explain("As host:port. Empty means no transfer is ever offered.")
    default String publicAddress() {
        return "";
    }

    /** The port of the standby proxy. */
    @Order(3)
    @Name("Standby port")
    @Key("standby-port")
    @NoExplanationNeeded
    default int standbyPort() {
        return 25566;
    }

    /** The address of the live proxy. The standby connects to it to find out when to send players back. */
    @Order(5)
    @Name("Live proxy address")
    @Key("live-address")
    @Explain("As host:port, inside the network. The public address cannot tell: Caddy answers it for the standby.")
    default String liveAddress() {
        return "proxy:25565";
    }

    /** Whether this proxy is the standby. */
    @Order(4)
    @Name("Standby")
    @Key("standby")
    @NoExplanationNeeded
    default boolean standby() {
        return false;
    }
}
