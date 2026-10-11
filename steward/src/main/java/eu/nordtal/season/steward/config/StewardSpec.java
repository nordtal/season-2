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
 * The {@code steward} group: the bank, the clocks and the agent Steward asks.
 *
 * Where versions come from is steward-agent's {@code runs} group, since the agent carries out every run.
 */
@ConfigSpec
public interface StewardSpec {

    @Order(9)
    @Name("HTTP timeout (seconds)")
    @Key("http-timeout-seconds")
    @Explain("For one call to steward-agent or steward-bunq.")
    default int httpTimeoutSeconds() {
        return 30;
    }

    @Order(13)
    @Name("bunq")
    @Key("bunq")
    @Explain("Without a token a season sells nothing.")
    BunqSpec bunq();

    /** What the nightly backup saves and stops; its clock only writes a request row, like the Backup button. */
    @Order(14)
    @Name("Backup")
    @Key("backup")
    @NoExplanationNeeded
    BackupSpec backup();

    /**
     * Off unless its time is set.
     *
     * When set, a whole-network update is asked for on the chosen days, with the same countdown a manual one gets.
     */
    @Order(18)
    @Name("Update schedule")
    @Key("update")
    @NoExplanationNeeded
    UpdateSpec update();

    @Order(17)
    @Name("Agent")
    @Key("agent")
    @Explain("Without a token, no container can be shown or changed.")
    AgentSpec agent();

    /** Payments: where steward-bunq answers and how often it is asked; purchases are the {@code access} group's. */
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

        /** How often the bank itself is asked; an HTTP call to bunq, separate from the bot's own poll. */
        @Order(3)
        @Name("Poll interval (seconds)")
        @Key("poll-interval-seconds")
        @NoExplanationNeeded
        default int pollIntervalSeconds() {
            return 30;
        }

        /** The first start stamps it itself. */
        @Order(4)
        @Name("Watermark")
        @Key("watermark")
        @Explain("Leave empty: a manual value risks booking historical payments.")
        default String watermark() {
            return "";
        }

        /** How many recent payments the fallback scan checks per poll, beyond the primary tab-matching path. */
        @Order(5)
        @Name("Recent payment count")
        @Key("recent-payment-count")
        @NoExplanationNeeded
        default int recentPaymentCount() {
            return 50;
        }
    }

    /** How this container reaches steward-agent, its only way to Docker and the volumes. */
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
        @Explain("Empty means Steward asks it nothing.")
        default String token() {
            return "";
        }
    }

    /** When a whole-network {@code UPDATE} is asked for without anybody pressing the button. */
    @ConfigSpec
    interface UpdateSpec {

        /** An admin can always start one by hand. */
        @Order(1)
        @Name("At")
        @Key("at")
        @Explain("Empty means no scheduled update.")
        default String at() {
            return "";
        }

        @Order(2)
        @Name("Days")
        @Key("days")
        @Explain("Ignored while the time is empty.")
        default List<String> days() {
            return List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
        }
    }
}
