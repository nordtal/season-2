package eu.nordtal.season.proxy.config;

import eu.nordtal.season.settings.rule.Positive;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** The {@code pack} group: the resource packs that the proxy offers to every player in the waiting room. */
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
    @Explain("steward-agent sets it after each pack build: the pack host and the zip's SHA-1.")
    default String url() {
        return "";
    }

    @Order(3)
    @Name("SHA-1")
    @Key("sha1")
    @Explain("steward-agent sets it with the URL. A mismatch fails every download on the network.")
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

    @Order(5)
    @Name("Map URL")
    @Key("map-url")
    @Explain("steward-agent sets it after each nightly map build. Empty, the proxy offers no map pack.")
    default String mapUrl() {
        return "";
    }

    @Order(6)
    @Name("Map SHA-1")
    @Key("map-sha1")
    @Explain("steward-agent sets it with the map URL. The map pack is the same for every language.")
    default String mapSha1() {
        return "";
    }

    /** The seconds until the proxy disconnects a player who has not answered the offer. */
    @Order(7)
    @Name("Apply timeout (seconds)")
    @Key("apply-timeout-seconds")
    @Positive
    @NoExplanationNeeded
    default int applyTimeoutSeconds() {
        return 180;
    }
}
