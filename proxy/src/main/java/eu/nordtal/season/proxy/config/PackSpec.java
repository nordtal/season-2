package eu.nordtal.season.proxy.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.Order;

/**
 * The {@code pack} group: the resource pack the proxy offers every player in the waiting room.
 *
 * {@link #sha1()} has no default, since the client refuses a pack whose hash disagrees with its URL.
 */
@ConfigSpec
public interface PackSpec {

    @Order(1)
    @Name("Enabled")
    @Key("enabled")
    @Explain("Off keeps the waiting room and drops only the pack offer; meant for a development proxy.")
    default boolean enabled() {
        return true;
    }

    @Order(2)
    @Name("URL")
    @Key("url")
    @Explain("The github.com/.../releases/download/... URL, never its redirect, which expires within the hour.")
    default String url() {
        return "";
    }

    @Order(3)
    @Name("SHA-1")
    @Key("sha1")
    @Explain("Copy it from the release's .sha1 file; a mismatch fails every download on the network.")
    default String sha1() {
        return "";
    }

    @Order(4)
    @Name("Force")
    @Key("force")
    @Explain("Keep it true: without it, a player who declined a pack before is disconnected unasked.")
    default boolean force() {
        return true;
    }

    @Order(5)
    @Name("Apply timeout (seconds)")
    @Key("apply-timeout-seconds")
    @Explain("How long an unanswered pack offer is allowed before the player is disconnected; generous on purpose.")
    default int applyTimeoutSeconds() {
        return 180;
    }
}
