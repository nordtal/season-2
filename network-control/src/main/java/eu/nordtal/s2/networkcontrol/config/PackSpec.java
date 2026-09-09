package eu.nordtal.s2.networkcontrol.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code config/pack.yml} - the resource pack the proxy offers every player on their way through
 * the waiting room.
 *
 * <p>Its own file rather than two more keys on {@link GateSpec}, because these values change on
 * every pack release and {@code gate.yml} - which decides who may join - should not be edited on
 * that rhythm.</p>
 *
 * <p>{@link #sha1()} has no default: the client is sent the URL and the hash and refuses the pack
 * if they disagree, so a guessed hash is wrong by construction.</p>
 *
 * <p>A config this module cannot make sense of fails the proxy closed, because letting players in
 * without the pack is what the waiting room exists to prevent. The escape hatch for a deployment
 * that genuinely has no pack yet is {@link #enabled()}.</p>
 */
@ConfigSpec(header = {
        "-------------------------------------------------------------------",
        "  network-control - the resource pack offered in the waiting room",
        "-------------------------------------------------------------------",
        "Every login lands on the 'limbo' backend first, whatever the phase,",
        "and the proxy offers this pack while they are there. Only once the",
        "client reports the pack applied is the player connected onward to",
        "the server the current season phase points at.",
        "",
        "THE PACK ZIP AND ITS SHA-1 BOTH COME FROM THE SAME GITHUB RELEASE.",
        "Attaching a new pack means a new release, a new url and a new sha1 -",
        "in that order, and both keys change together. The client is sent the",
        "url AND the hash and refuses the pack when they disagree, so a hash",
        "left behind from the previous release fails every download on the",
        "network at once.",
        "",
        "Every setting here can be overridden with an environment variable",
        "named NORDTAL_NETWORK_CONTROL_PACK_<PATH>, with '-' becoming '_':",
        "",
        "  sha1  ->  NORDTAL_NETWORK_CONTROL_PACK_SHA1",
        "",
        "The environment wins over this file and is never written back to it."
})
public interface PackSpec {

    @Order(1)
    @Key("enabled")
    @Comment({
            "Whether a pack is offered at all.",
            "",
            "TURNING THIS OFF DOES NOT REMOVE THE WAITING ROOM. Every login still lands on",
            "limbo first and is still released onto the phase's server from there; what goes",
            "away is the offer and the wait for it, so a player passes through limbo in the",
            "time it takes their client to load it.",
            "",
            "It exists for a development proxy. A production network runs with a pack: the",
            "glyphs the tab list, the nametags, the boards and the hunger games HUD are drawn",
            "with are in it, and without them those surfaces render as missing-glyph boxes."
    })
    default boolean enabled() {
        return true;
    }

    @Order(2)
    @Key("url")
    @Comment({
            "Where the client downloads the pack from - the GitHub release asset built by",
            ".github/workflows/release.yml.",
            "",
            "EMPTY BY DEFAULT AND THE PROXY REFUSES TO START WITHOUT IT while 'enabled' is",
            "true, which is this repository's standing rule for every value nobody can guess",
            "correctly. A default pointing at somebody's release would be worse than none.",
            "",
            "PUT THE github.com/.../releases/download/... URL HERE, NEVER THE ONE IT",
            "REDIRECTS TO: that URL answers a 302 to release-assets.githubusercontent.com, whose",
            "target is a SIGNED URL that expires within the hour. Pasting the resolved address",
            "here gives a pack that works this afternoon and fails tonight."
    })
    default String url() {
        return "";
    }

    @Order(3)
    @Key("sha1")
    @Comment({
            "The SHA-1 of exactly the zip at the url above, as 40 hex characters - the content",
            "of the .sha1 file the release carries next to the zip.",
            "",
            "NEVER TYPE THIS BY HAND AND NEVER COPY IT FROM AN OLDER RELEASE. The client",
            "checks it, refuses a pack that disagrees, and reports FAILED_DOWNLOAD - which",
            "looks exactly like a network problem and is not one. It is also what lets a",
            "client skip the download entirely when it already has this pack cached."
    })
    default String sha1() {
        return "";
    }

    @Order(4)
    @Key("force")
    @Comment({
            "Whether the offer is marked as required.",
            "",
            "TRUE IS THE DECIDED VALUE (2026-09-01) and this key is an emergency lever, not a",
            "setting to weigh up. Forcing is the only thing that makes the prompt appear for a",
            "player who has previously declined a pack or has server resource packs switched",
            "off in their client; without it those players are auto-declined and disconnected",
            "without ever seeing what they were asked.",
            "",
            "What it costs: on 1.17 and newer the CLIENT enforces a forced pack, and Velocity",
            "kicks a player who declines with its own generic text -",
            "PlayerResourcePackStatusEvent#setOverwriteKick throws on those versions rather than",
            "preventing it. Our own decline and failure screens therefore work by disconnecting",
            "the player first, from inside that awaited event."
    })
    default boolean force() {
        return true;
    }

    @Order(5)
    @Key("apply-timeout-seconds")
    @Comment({
            "How long a player may sit in the waiting room with an unanswered pack offer",
            "before they are disconnected with an explanation.",
            "",
            "The protocol has no answer for a client that never reports a status, and the",
            "waiting room is a black screen with a title, so 'downloading' and 'hung forever'",
            "look identical to the person staring at it. This turns the hang into a message and",
            "a rejoin. Generous by design: being too eager kicks somebody who was about to",
            "succeed."
    })
    default int applyTimeoutSeconds() {
        return 180;
    }
}
