package eu.nordtal.season.steward.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import eu.nordtal.season.spec.annotation.Secret;
import java.util.List;

/**
 * Holds the {@code steward} group: the bank, the clocks and the agent that Steward asks.
 * The {@code runs} group of steward-agent holds where versions come from.
 */
@ConfigSpec
public interface StewardSpec {

    @Order(9)
    @Name("HTTP timeout (seconds)")
    @Key("http-timeout-seconds")
    @NoExplanationNeeded
    default int httpTimeoutSeconds() {
        return 30;
    }

    @Order(13)
    @Name("bunq")
    @Key("bunq")
    @NoExplanationNeeded
    BunqSpec bunq();

    @Order(14)
    @Name("Backup")
    @Key("backup")
    @NoExplanationNeeded
    BackupSpec backup();

    /**
     * Stays off until a time is set.
     * Then it asks for a whole-network update on the chosen days, with the countdown of a manual update.
     */
    @Order(18)
    @Name("Update schedule")
    @Key("update")
    @NoExplanationNeeded
    UpdateSpec update();

    @Order(17)
    @Name("Agent")
    @Key("agent")
    @NoExplanationNeeded
    AgentSpec agent();

    @ConfigSpec
    interface BunqSpec {

        @Order(1)
        @Name("URL")
        @Key("url")
        @NoExplanationNeeded
        default String url() {
            return "http://steward-bunq:8082";
        }

        @Order(2)
        @Name("Token")
        @Key("token")
        @Secret
        @Explain("Empty turns payments off.")
        default String token() {
            return "";
        }

        @Order(3)
        @Name("Poll interval (seconds)")
        @Key("poll-interval-seconds")
        @NoExplanationNeeded
        default int pollIntervalSeconds() {
            return 30;
        }

        /** The first start sets it. */
        @Order(4)
        @Name("Watermark")
        @Key("watermark")
        @Explain("Leave empty: a manual value risks booking historical payments.")
        default String watermark() {
            return "";
        }

        @Order(5)
        @Name("Recent payment count")
        @Key("recent-payment-count")
        @NoExplanationNeeded
        default int recentPaymentCount() {
            return 50;
        }
    }

    @ConfigSpec
    interface AgentSpec {

        @Order(1)
        @Name("URL")
        @Key("url")
        @NoExplanationNeeded
        default String url() {
            return "http://steward-agent:8081";
        }

        @Order(2)
        @Name("Token")
        @Key("token")
        @Secret
        @Explain("Empty, no container can be shown or changed.")
        default String token() {
            return "";
        }
    }

    @ConfigSpec
    interface UpdateSpec {

        @Order(1)
        @Name("Time of day")
        @Key("at")
        @NoExplanationNeeded
        default String at() {
            return "";
        }

        @Order(2)
        @Name("Days")
        @Key("days")
        @NoExplanationNeeded
        default List<String> days() {
            return List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
        }
    }
}
