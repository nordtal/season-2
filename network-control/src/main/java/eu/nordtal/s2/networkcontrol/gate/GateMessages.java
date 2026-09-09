package eu.nordtal.s2.networkcontrol.gate;

import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.networkcontrol.config.GateSpec;
import eu.nordtal.s2.networkcontrol.launch.LaunchCountdown;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.time.Instant;
import java.util.Locale;

/**
 * Builds the disconnect and chat components the login gate, the expiry check and the phase router
 * show, so that {@link LoginGate} and {@link ExpiryWatch} stay about deciding what happens.
 * <p>
 * The methods the {@code routing} package needs are {@code public}; the rest are package-private.
 * </p>
 */
public final class GateMessages {

    private final Messages messages;
    private final GateSpec config;

    public GateMessages(final Messages messages, final GateSpec config) {
        this.messages = messages;
        this.config = config;
    }

    /**
     * The unlinked screen: English first, German underneath in grey italics, because the account
     * is unknown at this point and there is no locale to pick from.
     */
    Component notLinked(final String code, final Instant launch, final Instant now) {
        Component result = MessageRenderer.of(messages).format(Locale.ENGLISH, "gate.not-linked", "code", code)
                .appendNewline()
                .append(MessageRenderer.of(messages).format(Locale.GERMAN, "gate.not-linked", "code", code)
                        .color(NamedTextColor.GRAY)
                        .decorate(TextDecoration.ITALIC));
        if (hasInvite()) {
            result = result.appendNewline().appendNewline()
                    .append(MessageRenderer.of(messages).format(Locale.ENGLISH, "gate.not-linked.invite",
                            "invite", config.discordInviteUrl()));
        }
        return withCountdown(result, Locale.ENGLISH, launch, now);
    }

    /**
     * The account is no longer linked, discovered while the player was already connected. Unlike
     * the login screen it carries no fresh link code: issuing one is a database write, and this
     * path is not a login.
     */
    public Component unlinked(final Locale locale) {
        return MessageRenderer.of(messages).get(locale, "gate.unlinked");
    }

    /** Not a Discord member, or banned. */
    public Component notMember(final Locale locale) {
        Component result = MessageRenderer.of(messages).get(locale, "gate.not-member");
        if (hasInvite()) {
            result = result.appendNewline().appendNewline()
                    .append(MessageRenderer.of(messages).format(locale, "gate.not-member.invite",
                            "invite", config.discordInviteUrl()));
        }
        return result;
    }

    /** Linked, a member, but no access is running right now. */
    public Component noAccess(final Locale locale) {
        Component result = MessageRenderer.of(messages).get(locale, "gate.no-access");
        if (hasInvite()) {
            result = result.appendNewline().appendNewline()
                    .append(MessageRenderer.of(messages).format(locale, "gate.no-access.invite",
                            "invite", config.discordInviteUrl()));
        }
        return result;
    }

    /**
     * The network is in {@code MAINTENANCE} and there is <b>no {@code limbo} server to hold this
     * player in</b>.
     * <p>
     * Not a gate screen: a non-admin is admitted during maintenance and routed to {@code limbo},
     * where the explanation is shown. This is the fallback for {@code gate.yml#server-limbo} naming
     * a server the proxy does not have.
     * </p>
     */
    public Component maintenance(final Locale locale) {
        return MessageRenderer.of(messages).get(locale, "gate.maintenance");
    }

    /**
     * The phase says this player belongs on a server this proxy does not have registered, and the
     * phase is not {@code MAINTENANCE} (which has its own screen above). Always a config error -
     * {@code gate.yml}'s server names not matching {@code velocity.toml}.
     */
    public Component noServer(final Locale locale) {
        return MessageRenderer.of(messages).get(locale, "gate.no-server");
    }

    /**
     * The network is at {@code network.yml#max-players} and this player is not an admin. The
     * numbers are in the text on purpose: "312 of 312" reads as a limit somebody chose, where
     * "full" alone reads as something broken.
     */
    Component full(final Locale locale, final int online, final int max) {
        return MessageRenderer.of(messages).format(locale, "gate.full", "online", online, "max", max);
    }

    /**
     * {@code PRE_LAUNCH}, linked, nothing bought yet: the invitation to buy the first month now so
     * that the SMP is playable the moment the event ends.
     */
    public Component preLaunchBuy(final Locale locale, final Instant launch, final Instant now) {
        Component result = MessageRenderer.of(messages).get(locale, "gate.pre-launch.buy");
        if (hasInvite()) {
            result = result.appendNewline()
                    .append(MessageRenderer.of(messages).format(locale, "gate.no-access.invite",
                            "invite", config.discordInviteUrl()));
        }
        return withCountdown(result, locale, launch, now);
    }

    /** {@code PRE_LAUNCH}, linked, and a period already bought. Nothing to do but wait. */
    public Component preLaunchReady(final Locale locale, final Instant launch, final Instant now) {
        return withCountdown(MessageRenderer.of(messages).get(locale, "gate.pre-launch.ready"), locale, launch, now);
    }

    /**
     * Appends the countdown line, in grey, with a blank line above it - or nothing at all when
     * there is no countdown to show, which is every phase but {@code PRE_LAUNCH}.
     */
    private Component withCountdown(final Component screen, final Locale locale, final Instant launch,
                                    final Instant now) {
        if (now == null) {
            return screen;
        }
        return screen.appendNewline().appendNewline()
                .append(LaunchCountdown.component(messages, locale, launch, now)
                        .color(NamedTextColor.GRAY));
    }

    /** The database is unreachable and the fallback cache has nothing usable for this player. */
    public Component trouble(final Locale locale) {
        return MessageRenderer.of(messages).get(locale, "gate.trouble");
    }

    /** The in-chat warning shown a few minutes before access runs out. */
    Component expiryWarning(final Locale locale, final long minutesRemaining) {
        return MessageRenderer.of(messages).format(locale, "gate.expiry.warning", "minutes", minutesRemaining);
    }

    /** The disconnect shown the moment access actually runs out mid-session. */
    Component expired(final Locale locale) {
        return MessageRenderer.of(messages).get(locale, "gate.expiry.expired");
    }

    private boolean hasInvite() {
        return config.discordInviteUrl() != null && !config.discordInviteUrl().isBlank();
    }
}
