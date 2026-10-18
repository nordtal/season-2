package eu.nordtal.season.proxy.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** The {@code pack} group: the resource pack that the proxy offers to every player in the waiting room. */
@ConfigSpec
public interface PackSpec {

    /** Whether the proxy offers the pack. */
    @Order(1)
    @Name("Enabled")
    @Key("enabled")
    @NoExplanationNeeded
    default boolean enabled() {
        return true;
    }

    @Order(2)
    @Name("URL")
    @Key("url")
    @Explain("The releases/download URL, never its redirect, which expires within the hour.")
    default String url() {
        return "";
    }

    @Order(3)
    @Name("SHA-1")
    @Key("sha1")
    @Explain("A mismatch fails every download on the network.")
    default String sha1() {
        return "";
    }

    @Order(4)
    @Name("Force")
    @Key("force")
    @Explain("Off, a player who declined a pack before is disconnected unasked.")
    default boolean force() {
        return true;
    }

    /** The seconds until the proxy disconnects a player who has not answered the offer. */
    @Order(5)
    @Name("Apply timeout (seconds)")
    @Key("apply-timeout-seconds")
    @NoExplanationNeeded
    default int applyTimeoutSeconds() {
        return 180;
    }
}
