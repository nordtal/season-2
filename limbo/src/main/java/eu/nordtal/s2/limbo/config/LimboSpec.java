package eu.nordtal.s2.limbo.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code plugins/limbo/config.yml}: the waiting room's settings.
 *
 * The waiting reason is not here: the proxy sends it on {@code nordtal:limbo}, so the title has one source.
 */
@ConfigSpec(
        header = {
            "-------------------------------------------------------------------",
            "  limbo: the season 2 waiting room",
            "-------------------------------------------------------------------",
            "Every login lands here first and stays until the proxy moves it on.",
            "The screen shows one title; the proxy sends the reason, and the",
            "words live in messages/limbo/<language>.properties.",
            "",
            "Every setting can be overridden with an environment variable named",
            "NORDTAL_LIMBO_<SETTING>, with '-' becoming '_', for example",
            "NORDTAL_LIMBO_WORLD_NAME. The environment wins over this file and is",
            "never written back to it."
        })
public interface LimboSpec {

    @Order(1)
    @Name("World name")
    @Key("world-name")
    @Comment({
        "The name of the empty world this plugin creates and puts everybody in.",
        "It is not the server's level-name world. Deleting it is safe; it is rebuilt empty."
    })
    @Explain(
            "The empty world this plugin creates and puts everybody in; safe to delete, it is rebuilt on the next start.")
    default String worldName() {
        return "limbo";
    }

    @Order(2)
    @Name("Spawn Y")
    @Key("spawn-y")
    @Comment({
        "The height everybody stands at, well clear of the void.",
        "A player who stops flying falls from here before the plugin puts them back."
    })
    @Explain("How far above the void players stand, so a stray fall lands them back rather than into nothing.")
    default int spawnY() {
        return 64;
    }

    @Order(3)
    @Name("Title refresh (seconds)")
    @Key("title-refresh-seconds")
    @Comment({
        "How often the waiting title is re-sent to every player here.",
        "A title expires, and a black screen with no title looks like a crash.",
        "The title stays for twice this value."
    })
    @Explain(
            "How often the waiting title is re-sent; too infrequent and the screen goes black between refreshes, looking like a crash.")
    default int titleRefreshSeconds() {
        return 4;
    }

    @Order(4)
    @Name("Blindness")
    @Key("blindness")
    @Comment({
        "Whether players here are blinded, which turns the empty world's sky into black.",
        "Turn it off only while working on this plugin, never in production."
    })
    @Explain(
            "Turns the empty world's sky into a black screen. Disable only while working on this plugin, never in production.")
    default boolean blindness() {
        return true;
    }

    @Order(5)
    @Name("Admin poll interval (seconds)")
    @Key("admin-poll-interval-seconds")
    @Comment({
        "How often this server re-reads who is an admin (discord_user.admin), in seconds.",
        "THIS POLL IS THE GUARANTEE a revoked admin loses operator, not the LISTEN below."
    })
    @Explain("How often admin status is re-read; this poll, not the LISTEN switch below, is the guarantee.")
    default int adminPollIntervalSeconds() {
        return 30;
    }

    @Order(6)
    @Name("Listen for admin changes")
    @Key("admin-listen-enabled")
    @Comment({
        "Whether to also open a dedicated LISTEN nordtal_admin connection, outside the pool.",
        "It only makes a revocation feel instant; turning it off costs latency and nothing else."
    })
    @Explain("Makes a revocation feel instant instead of waiting for the next poll; off only costs latency.")
    default boolean adminListenEnabled() {
        return true;
    }
}
