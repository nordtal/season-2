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
    @Explain("How long any single call to steward-agent or steward-bunq may wait before Steward gives up.")
    default int httpTimeoutSeconds() {
        return 30;
    }

    @Order(13)
    @Name("bunq")
    @Key("bunq")
    @Explain("How payments reach the bank through steward-bunq. Without a token a season sells nothing.")
    BunqSpec bunq();

    @Order(14)
    @Name("Backup")
    @Key("backup")
    @Explain("What the nightly backup saves and stops. Its clock only writes a request row, like the Backup button.")
    BackupSpec backup();

    @Order(18)
    @Name("Update schedule")
    @Key("update")
    @Explain(
            "Off unless update.at is set. When set, a whole-network update is asked for on the chosen days, with the same countdown a manual one gets.")
    UpdateSpec update();

    @Order(17)
    @Name("Agent")
    @Key("agent")
    @Explain(
            "Steward's only way to the containers and the volumes. Without a token below, nothing about a container can be shown or done.")
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
        @Explain("The secret steward-bunq expects. Empty turns payments off: nothing here asks the bank anything.")
        default String token() {
            return "";
        }

        @Order(3)
        @Name("Poll interval (seconds)")
        @Key("poll-interval-seconds")
        @Explain("How often the bank itself is asked. This is an HTTP call to bunq, separate from the bot's own poll.")
        default int pollIntervalSeconds() {
            return 30;
        }

        @Order(4)
        @Name("Watermark")
        @Key("watermark")
        @Explain("Leave empty: the first start stamps this itself. A manual value risks booking historical payments.")
        default String watermark() {
            return "";
        }

        @Order(5)
        @Name("Recent payment count")
        @Key("recent-payment-count")
        @Explain("How many recent payments the fallback scan checks per poll, beyond the primary tab-matching path.")
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
        @Explain("The secret steward-agent expects. Empty means this container asks it nothing.")
        default String token() {
            return "";
        }
    }

    /** When a whole-network {@code UPDATE} is asked for without anybody pressing the button. */
    @ConfigSpec
    interface UpdateSpec {

        @Order(1)
        @Name("At")
        @Key("at")
        @Explain("Empty means no scheduled update. An admin can always start one by hand.")
        default String at() {
            return "";
        }

        @Order(2)
        @Name("Days")
        @Key("days")
        @Explain("Which weekdays the scheduled update runs on. Ignored while update.at is empty.")
        default List<String> days() {
            return List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
        }
    }
}
