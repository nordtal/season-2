package eu.nordtal.s2.steward.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Secret;
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
    @Comment("How long any single call to steward-agent or steward-bunq may take before Steward gives up.")
    @Explain("How long any single call to steward-agent or steward-bunq may wait before Steward gives up.")
    default int httpTimeoutSeconds() {
        return 30;
    }

    @Order(13)
    @Name("bunq")
    @Key("bunq")
    @Comment({
        "Payments, through steward-bunq, the only container that holds a bunq credential or calls bunq.",
        "A season without an account works except for buying access."
    })
    @Explain("How payments reach the bank through steward-bunq. Without a token a season sells nothing.")
    BunqSpec bunq();

    @Order(14)
    @Name("Backup")
    @Key("backup")
    @Comment({
        "The nightly volume backup: which volumes are saved and which services are stopped",
        "while they are. The clock writes a request row; see backup.at."
    })
    @Explain("What the nightly backup saves and stops. Its clock only writes a request row, like the Backup button.")
    BackupSpec backup();

    @Order(18)
    @Name("Update schedule")
    @Key("update")
    @Comment({
        "An optional clock that asks for a whole-network UPDATE on the days and at the time",
        "below, through the same request row as the Update button. Off while update.at is empty.",
        "Saving this file through Steward re-arms both clocks."
    })
    @Explain(
            "Off unless update.at is set. When set, a whole-network update is asked for on the chosen days, with the same countdown a manual one gets.")
    UpdateSpec update();

    @Order(17)
    @Name("Agent")
    @Key("agent")
    @Comment({
        "steward-agent, the one process that reaches Docker and the volumes: container state,",
        "logs, the console, the backups and the plan of the next run. Without a token it asks nothing."
    })
    @Explain(
            "Steward's only way to the containers and the volumes. Without a token below, nothing about a container can be shown or done.")
    AgentSpec agent();

    /** Payments: where steward-bunq answers and how often it is asked; purchases are the {@code access} group's. */
    @ConfigSpec
    interface BunqSpec {

        @Order(1)
        @Name("URL")
        @Key("url")
        @Comment("Where steward-bunq, the one process holding the bank key, answers from inside this container.")
        @NoExplanationNeeded
        default String url() {
            return "http://steward-bunq:8082";
        }

        @Order(2)
        @Name("Token")
        @Key("token")
        @Comment({
            "The shared secret sent as X-Steward-Token to steward-bunq. Empty means this container",
            "never asks the bank anything, so payments are off. Set NORDTAL_STEWARD_BUNQ_TOKEN instead."
        })
        @Secret
        @Explain("The secret steward-bunq expects. Empty turns payments off: nothing here asks the bank anything.")
        default String token() {
            return "";
        }

        @Order(3)
        @Name("Poll interval (seconds)")
        @Key("poll-interval-seconds")
        @Comment({
            "How often bunq is asked about open tabs and recent payments. Separate from the",
            "bot's database poll because this one is HTTP to a bank."
        })
        @Explain("How often the bank itself is asked. This is an HTTP call to bunq, separate from the bot's own poll.")
        default int pollIntervalSeconds() {
            return 30;
        }

        @Order(4)
        @Name("Watermark")
        @Key("watermark")
        @Comment({
            "Payments created before this instant are ignored, completely and forever.",
            "Leave it empty: the first start stores its own instant. A value here, ISO-8601 in UTC,",
            "overrides the stored one without replacing it."
        })
        @Explain("Leave empty: the first start stamps this itself. A manual value risks booking historical payments.")
        default String watermark() {
            return "";
        }

        @Order(5)
        @Name("Recent payment count")
        @Key("recent-payment-count")
        @Comment({
            "How many recent payments the fallback reference scan looks at per poll, for money",
            "that reached the account outside a tab."
        })
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
        @Comment("Where steward-agent's HTTP API answers from inside this container.")
        @NoExplanationNeeded
        default String url() {
            return "http://steward-agent:8081";
        }

        @Order(2)
        @Name("Token")
        @Key("token")
        @Comment({
            "The shared secret sent as X-Steward-Token with every request to steward-agent.",
            "Empty means this container never asks. Set NORDTAL_STEWARD_AGENT_TOKEN instead."
        })
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
        @Comment("HH:mm in the network's default time zone, or empty for no scheduled update at all.")
        @Explain("Empty means no scheduled update. An admin can always start one by hand.")
        default String at() {
            return "";
        }

        @Order(2)
        @Name("Days")
        @Key("days")
        @Comment("Which weekdays the scheduled update runs on, read exactly like backup.days.")
        @Explain("Which weekdays the scheduled update runs on. Ignored while update.at is empty.")
        default List<String> days() {
            return List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
        }
    }
}
