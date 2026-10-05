package eu.nordtal.season.proxy.config;

import eu.nordtal.season.spec.annotation.Comment;
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
    @Comment({
        "Whether a pack is offered at all. Off keeps the waiting room and drops only the offer.",
        "Meant for a development proxy: without the pack, every glyph renders as a box."
    })
    @Explain("Off keeps the waiting room and drops only the pack offer; meant for a development proxy.")
    default boolean enabled() {
        return true;
    }

    @Order(2)
    @Name("URL")
    @Key("url")
    @Comment({
        "The release asset's github.com/.../releases/download/... URL. Required while enabled.",
        "Never the address it redirects to: that one is signed and expires within the hour."
    })
    @Explain("The github.com/.../releases/download/... URL, never its redirect, which expires within the hour.")
    default String url() {
        return "";
    }

    @Order(3)
    @Name("SHA-1")
    @Key("sha1")
    @Comment({
        "The SHA-1 of the zip at the url above: the content of the release's .sha1 file.",
        "A mismatch makes every client report FAILED_DOWNLOAD, which looks like a network fault."
    })
    @Explain("Copy it from the release's .sha1 file; a mismatch fails every download on the network.")
    default String sha1() {
        return "";
    }

    @Order(4)
    @Name("Force")
    @Key("force")
    @Comment({
        "Whether the offer is marked as required. Keep it true; it is an emergency lever.",
        "Without it, a player who declined a pack before is disconnected without being asked."
    })
    @Explain("Keep it true: without it, a player who declined a pack before is disconnected unasked.")
    default boolean force() {
        return true;
    }

    @Order(5)
    @Name("Apply timeout (seconds)")
    @Key("apply-timeout-seconds")
    @Comment({
        "How long a pack offer may go unanswered before the player is disconnected with an",
        "explanation. Generous on purpose: too eager kicks somebody about to succeed."
    })
    @Explain("How long an unanswered pack offer is allowed before the player is disconnected; generous on purpose.")
    default int applyTimeoutSeconds() {
        return 180;
    }
}
