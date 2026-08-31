package eu.nordtal.s2.networkcontrol.gate;

import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.networkcontrol.config.GateSpec;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Locale;

/**
 * Builds the disconnect and chat components the login gate, the expiry check and the phase router
 * show. Kept separate from {@link LoginGate} and {@link ExpiryWatch} so those stay about deciding
 * what happens, not about how it is rendered.
 * <p>
 * The methods the {@code routing} package needs are {@code public}; the rest stay package-private.
 * That is the whole reason for the split visibility - there is no second rule behind it.
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
    Component notLinked(final String code) {
        Component result = Component.text(messages.format(Locale.ENGLISH, "gate.not-linked", "code", code))
                .appendNewline()
                .append(Component.text(messages.format(Locale.GERMAN, "gate.not-linked", "code", code))
                        .color(NamedTextColor.GRAY)
                        .decorate(TextDecoration.ITALIC));
        if (hasInvite()) {
            result = result.appendNewline().appendNewline()
                    .append(Component.text(messages.format(Locale.ENGLISH, "gate.not-linked.invite",
                            "invite", config.discordInviteUrl())));
        }
        return result;
    }

    /**
     * The account is no longer linked, discovered while the player was already connected - the
     * self-service {@code /unlink} in Discord is what produces this. The login screen for the same
     * situation is {@link #notLinked(String)}, which carries a fresh code; there is no code to hand
     * out here, because issuing one is a database write on a path that is not a login.
     */
    public Component unlinked(final Locale locale) {
        return Component.text(messages.get(locale, "gate.unlinked"));
    }

    /** Not a Discord member, or banned. */
    public Component notMember(final Locale locale) {
        Component result = Component.text(messages.get(locale, "gate.not-member"));
        if (hasInvite()) {
            result = result.appendNewline().appendNewline()
                    .append(Component.text(messages.format(locale, "gate.not-member.invite",
                            "invite", config.discordInviteUrl())));
        }
        return result;
    }

    /** Linked, a member, but no access is running right now. */
    public Component noAccess(final Locale locale) {
        Component result = Component.text(messages.get(locale, "gate.no-access"));
        if (hasInvite()) {
            result = result.appendNewline().appendNewline()
                    .append(Component.text(messages.format(locale, "gate.no-access.invite",
                            "invite", config.discordInviteUrl())));
        }
        return result;
    }

    /**
     * The network is in {@code MAINTENANCE} and there is <b>no {@code limbo} server to hold this
     * player in</b>.
     * <p>
     * This is no longer a gate screen. Since the 2026-08-31 reversal a non-admin is admitted during
     * maintenance and routed to {@code limbo}, where the explanation is shown; this component is
     * what happens when {@code gate.yml#server-limbo} names a server the proxy does not have. It is
     * the "disconnect" half of docs/season-phases.md's original either/or, kept as the fallback for
     * exactly the case in which the "hold in limbo" half is impossible.
     * </p>
     * <p>
     * Rendered in the player's own language, like every other screen shown to somebody we have
     * identified. docs/season-phases.md's flowchart calls this screen "a bilingual explanation",
     * which is how the <em>unlinked</em> screen has to work - there the account is unknown and
     * there is no language to pick. By this branch the account is linked and
     * {@code discord_user.locale} is known, so showing both languages would be a downgrade rather
     * than a courtesy. Flagged as a documentation contradiction rather than silently resolved.
     * </p>
     */
    public Component maintenance(final Locale locale) {
        return Component.text(messages.get(locale, "gate.maintenance"));
    }

    /**
     * The phase says this player belongs on a server this proxy does not have registered, and the
     * phase is not {@code MAINTENANCE} (which has its own, more informative screen above).
     * <p>
     * Nothing in docs/ says what a player should see here, because nothing in docs/ contemplates a
     * phase whose backend is missing. This is a config error - {@code gate.yml}'s server names not
     * matching {@code velocity.toml} - and the screen says so in the only terms a player can act
     * on: it is not their fault and an admin has to fix it.
     * </p>
     */
    public Component noServer(final Locale locale) {
        return Component.text(messages.get(locale, "gate.no-server"));
    }

    /** The database is unreachable and the fallback cache has nothing usable for this player. */
    public Component trouble(final Locale locale) {
        return Component.text(messages.get(locale, "gate.trouble"));
    }

    /** The in-chat warning shown a few minutes before access runs out. */
    Component expiryWarning(final Locale locale, final long minutesRemaining) {
        return Component.text(messages.format(locale, "gate.expiry.warning", "minutes", minutesRemaining));
    }

    /** The disconnect shown the moment access actually runs out mid-session. */
    Component expired(final Locale locale) {
        return Component.text(messages.get(locale, "gate.expiry.expired"));
    }

    private boolean hasInvite() {
        return config.discordInviteUrl() != null && !config.discordInviteUrl().isBlank();
    }
}
