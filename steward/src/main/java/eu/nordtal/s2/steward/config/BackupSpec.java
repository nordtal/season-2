package eu.nordtal.s2.steward.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Secret;
import java.util.List;

/** When the nightly {@code BACKUP} is asked for, and where an offsite copy would go; what it keeps is the agent's. */
@ConfigSpec
public interface BackupSpec {

    @Order(7)
    @Name("Time of day")
    @Key("at")
    @Comment({
        "Local time of day the nightly backup is asked for, HH:mm, in this container's TZ.",
        "Empty means none. The zone and the next firing are logged on every start."
    })
    @Explain("Empty means no nightly backup at all, and nothing else in the stack makes one.")
    default String at() {
        return "04:45";
    }

    @Order(8)
    @Name("Days")
    @Key("days")
    @Comment({
        "Which weekdays the nightly backup runs on: full names or three-letter forms, any case.",
        "A word that is not a weekday is logged and ignored. An empty list means no nightly",
        "backup, and retention counts days, so gaps stretch the daily window."
    })
    @Explain(
            "Which weekdays the nightly backup runs on. All seven by default. An empty list means no nightly backup at all.")
    default List<String> days() {
        return List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
    }

    @Order(10)
    @Name("Remote")
    @Key("remote")
    @Comment({
        "Where an offsite copy goes. An empty endpoint means none, so every archive stays on",
        "the same disk as its volume. Nothing uploads to it yet."
    })
    @Explain("Where a copy goes that is not on this disk. An empty endpoint means there is none.")
    RemoteSpec remote();

    /** The offsite target: an S3 bucket and its two credentials, which the interface only reports as set or not. */
    @ConfigSpec
    interface RemoteSpec {

        @Order(1)
        @Name("Endpoint")
        @Key("endpoint")
        @Comment({
            "The S3 endpoint, with scheme. Empty means no offsite copy, and every other key here",
            "is then unread."
        })
        @Explain("Empty means there is no offsite copy.")
        default String endpoint() {
            return "";
        }

        @Order(2)
        @Name("Bucket")
        @Key("bucket")
        @Comment("The bucket the archives are written into.")
        @Explain("The bucket the archives are written into.")
        default String bucket() {
            return "";
        }

        @Order(3)
        @Name("Prefix")
        @Key("prefix")
        @Comment({
            "A path inside the bucket, so one bucket can hold more than one deployment.",
            "Empty writes to the root of the bucket."
        })
        @Explain("Lets one bucket hold more than one deployment. Empty writes to the root of the bucket.")
        default String prefix() {
            return "";
        }

        @Order(4)
        @Name("Access key")
        @Key("access-key")
        @Secret
        @Comment("The access key id. Sent to a browser as \"set\" or \"not set\", never as itself.")
        @Explain("Never leaves this process: the interface is told whether it is set, not what it is.")
        default String accessKey() {
            return "";
        }

        @Order(5)
        @Name("Secret key")
        @Key("secret-key")
        @Secret
        @Comment("The secret access key. Sent to a browser as \"set\" or \"not set\", never as itself.")
        @Explain("Never leaves this process: the interface is told whether it is set, not what it is.")
        default String secretKey() {
            return "";
        }
    }
}
