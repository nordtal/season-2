package eu.nordtal.season.steward.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;

/** Holds the internal endpoints that the deployment sets for Steward. */
@ConfigSpec
public interface ConnectionSpec {

    @Key("agent-url")
    @NoExplanationNeeded
    default String agentUrl() {
        return "http://steward-agent:8081";
    }

    @Key("bunq-url")
    @NoExplanationNeeded
    default String bunqUrl() {
        return "http://steward-bunq:8082";
    }
}
