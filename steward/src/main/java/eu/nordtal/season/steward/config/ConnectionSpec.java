package eu.nordtal.season.steward.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;

/** Holds the internal endpoints that the deployment sets for Steward. Neither has a default. */
@ConfigSpec
public interface ConnectionSpec {

    @Key("agent-url")
    @NoExplanationNeeded
    default String agentUrl() {
        return "";
    }

    @Key("bunq-url")
    @NoExplanationNeeded
    default String bunqUrl() {
        return "";
    }
}
