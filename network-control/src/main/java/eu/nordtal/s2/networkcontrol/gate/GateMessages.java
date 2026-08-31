package eu.nordtal.s2.networkcontrol.gate;

import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.networkcontrol.config.GateSpec;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Locale;

/**
 * Builds the disconnect and chat components the login gate and the expiry check show. Kept
 * separate from {@link LoginGate} and {@link ExpiryWatch} so those two stay about deciding what
 * happens, not about how it is rendered.
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

    /** Not a Discord member, or banned. */
    Component notMember(final Locale locale) {
        Component result = Component.text(messages.get(locale, "gate.not-member"));
        if (hasInvite()) {
            result = result.appendNewline().appendNewline()
                    .append(Component.text(messages.format(locale, "gate.not-member.invite",
                            "invite", config.discordInviteUrl())));
        }
        return result;
    }

    /** Linked, a member, but no access is running right now. */
    Component noAccess(final Locale locale) {
        Component result = Component.text(messages.get(locale, "gate.no-access"));
        if (hasInvite()) {
            result = result.appendNewline().appendNewline()
                    .append(Component.text(messages.format(locale, "gate.no-access.invite",
                            "invite", config.discordInviteUrl())));
        }
        return result;
    }

    /**
     * The network is in {@code MAINTENANCE} and this player is not an admin.
     * <p>
     * Rendered in the player's own language, like every other screen shown to somebody we have
     * identified. docs/season-phases.md's flowchart calls this screen "a bilingual explanation",
     * which is how the <em>unlinked</em> screen has to work - there the account is unknown and
     * there is no language to pick. By this branch the account is linked and
     * {@code discord_user.locale} is on the row we just read, so showing both languages would be a
     * downgrade rather than a courtesy. Flagged as a documentation contradiction rather than
     * silently resolved.
     * </p>
     */
    Component maintenance(final Locale locale) {
        return Component.text(messages.get(locale, "gate.maintenance"));
    }

    /** The database is unreachable and the fallback cache has nothing usable for this player. */
    Component trouble(final Locale locale) {
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
