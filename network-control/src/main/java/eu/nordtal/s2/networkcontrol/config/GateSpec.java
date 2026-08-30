package eu.nordtal.s2.networkcontrol.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code config/gate.yml} - everything the login gate and the mid-session expiry check need that
 * is not a database credential. See docs/access-stage-c.md.
 * <p>
 * <b>{@link #linkCodeTtlMinutes()} duplicates {@code access-bot}'s
 * {@code access.yml#link-code-ttl-minutes}.</b> That field's own comment says the value "lives
 * [in access.yml] because the bot owns the sweep that deletes expired ones" - but the bot's sweep
 * ({@code ReconcileDao#deleteExpiredLinkCodes}) only ever compares {@code link_code.expires} to
 * {@code now()}; it does not read that config value at all, and nothing before stage C did either,
 * since issuing a code with a TTL is exactly the stage C responsibility that did not exist yet.
 * The proxy is the process that actually calls {@code AccessDirectory#issueLinkCode} and therefore
 * the only process that can act on a TTL, so it needs its own copy here - the two are independently
 * configured and an operator who changes one without the other will not be told. This is flagged in
 * the stage C completion report rather than silently resolved one way; see there for the two ways
 * to remove the duplication.
 * </p>
 */
@ConfigSpec(header = {
        "-------------------------------------------------------------------",
        "  network-control - the login gate and the mid-session expiry check",
        "-------------------------------------------------------------------",
        "Every setting here can be overridden with an environment variable",
        "named NORDTAL_NETWORK_CONTROL_GATE_<PATH>, with '.' and '-' both",
        "becoming '_':",
        "",
        "  link-code-ttl-minutes  ->  NORDTAL_NETWORK_CONTROL_GATE_LINK_CODE_TTL_MINUTES",
        "",
        "The environment wins over this file and is never written back into it."
})
public interface GateSpec {

    @Order(1)
    @Key("discord-invite-url")
    @Comment({
            "Shown on the two disconnect screens that point a player at Discord: not yet a",
            "member (or banned), and not linked. Empty is allowed - the message still makes",
            "sense without a clickable link - but filling it in is what makes the screen",
            "actually useful to somebody who has never joined the Discord server."
    })
    default String discordInviteUrl() {
        return "";
    }

    @Order(2)
    @Key("link-code-ttl-minutes")
    @Comment({
            "How long a freshly issued link code stays valid. A repeated join attempt inside",
            "this window returns the same code rather than minting a new one.",
            "",
            "See this file's class-level documentation for why this duplicates",
            "access-bot's access.yml#link-code-ttl-minutes rather than sharing it."
    })
    default int linkCodeTtlMinutes() {
        return 10;
    }

    @Order(3)
    @Key("fallback-cache-window-minutes")
    @Comment({
            "How long a player's last-known state stays usable once the database becomes",
            "unreachable. Only entries with active access at the moment they were cached are",
            "ever used to let somebody in; everyone else is refused outright. A long outage",
            "closes the door rather than leaving it open forever - see docs/access-stage-c.md."
    })
    default int fallbackCacheWindowMinutes() {
        return 15;
    }

    @Order(4)
    @Key("expiry-check-interval-seconds")
    @Comment({
            "How often every connected, linked player's access is re-checked against the",
            "database while they are online. Re-checked, not just counted down from the",
            "value seen at login: this is also what notices a mid-session /revoke-access or",
            "a renewal that pushes the deadline back out.",
            "",
            "A database hiccup during one pass is skipped rather than treated as a mass",
            "expiry - the fallback cache is a login-time concept only; nobody already",
            "connected is kicked because one periodic query failed."
    })
    default int expiryCheckIntervalSeconds() {
        return 60;
    }

    @Order(5)
    @Key("expiry-warning-lead-minutes")
    @Comment("How long before access ends the in-chat warning is shown, once, per remaining period.")
    default int expiryWarningLeadMinutes() {
        return 5;
    }
}
