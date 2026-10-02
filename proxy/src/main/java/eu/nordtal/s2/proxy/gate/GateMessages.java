package eu.nordtal.s2.proxy.gate;

import static eu.nordtal.s2.proxy.ProxyMessages.MESSAGES;

import eu.nordtal.s2.common.language.Locales;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.proxy.config.GateSpec;
import eu.nordtal.s2.proxy.launch.LaunchCountdown;
import java.time.Instant;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jspecify.annotations.Nullable;

/** Builds the disconnect and chat components the login gate, the expiry check and the phase router show. */
public final class GateMessages {

    private final Messages messages;
    private final GateSpec config;

    public GateMessages(final Messages messages, final GateSpec config) {
        this.messages = messages;
        this.config = config;
    }

    /** The unlinked screen: English first, every other language underneath, since the account has no locale yet. */
    Component notLinked(final String code, final @Nullable Instant launch, final @Nullable Instant now) {
        Component result = inEveryLanguage(messages, MESSAGES.gate().notLinked(code));
        if (hasInvite()) {
            result = result.appendNewline()
                    .appendNewline()
                    .append(MessageRenderer.of(messages)
                            .format(
                                    Locale.ENGLISH,
                                    MESSAGES.gate().notLinkedSection().invite(config.discordInviteUrl())));
        }
        return withCountdown(result, Locale.ENGLISH, launch, now);
    }

    /** The account is no longer linked, found mid-session; unlike the login screen it carries no link code. */
    public Component unlinked(final Locale locale) {
        return MessageRenderer.of(messages).format(locale, MESSAGES.gate().unlinked());
    }

    /** The screen for a login between a proxy swap parking the network and the proxy stopping. */
    public Component restarting(final Locale locale) {
        return MessageRenderer.of(messages).format(locale, MESSAGES.gate().restarting());
    }

    /** Not a Discord member, or banned. */
    public Component notMember(final Locale locale) {
        Component result =
                MessageRenderer.of(messages).format(locale, MESSAGES.gate().notMember());
        if (hasInvite()) {
            result = result.appendNewline()
                    .appendNewline()
                    .append(MessageRenderer.of(messages)
                            .format(locale, MESSAGES.gate().notMemberSection().invite(config.discordInviteUrl())));
        }
        return result;
    }

    /** Linked, a member, but no access is running right now. */
    public Component noAccess(final Locale locale) {
        Component result =
                MessageRenderer.of(messages).format(locale, MESSAGES.gate().noAccess());
        if (hasInvite()) {
            result = result.appendNewline()
                    .appendNewline()
                    .append(MessageRenderer.of(messages)
                            .format(locale, MESSAGES.gate().noAccessSection().invite(config.discordInviteUrl())));
        }
        return result;
    }

    /** The {@code MAINTENANCE} fallback for when {@code gate.yml#server-limbo} names no registered server. */
    public Component maintenance(final Locale locale) {
        return MessageRenderer.of(messages).format(locale, MESSAGES.gate().maintenance());
    }

    /** The phase names a server this proxy does not have: {@code gate.yml} disagrees with {@code velocity.toml}. */
    public Component noServer(final Locale locale) {
        return MessageRenderer.of(messages).format(locale, MESSAGES.gate().noServer());
    }

    /** The network is at the {@code max-players} of its players group and this player is not an admin. */
    Component full(final Locale locale, final int online, final int max) {
        return MessageRenderer.of(messages).format(locale, MESSAGES.gate().full(online, max));
    }

    /** {@code PRE_LAUNCH}, linked, nothing bought yet: the invitation to buy the first month now. */
    public Component preLaunchBuy(final Locale locale, final @Nullable Instant launch, final @Nullable Instant now) {
        Component result = MessageRenderer.of(messages)
                .format(locale, MESSAGES.gate().preLaunch().buy());
        if (hasInvite()) {
            result = result.appendNewline()
                    .append(MessageRenderer.of(messages)
                            .format(locale, MESSAGES.gate().noAccessSection().invite(config.discordInviteUrl())));
        }
        return withCountdown(result, locale, launch, now);
    }

    /** {@code PRE_LAUNCH}, linked, and a period already bought. Nothing to do but wait. */
    public Component preLaunchReady(final Locale locale, final @Nullable Instant launch, final @Nullable Instant now) {
        return withCountdown(
                MessageRenderer.of(messages)
                        .format(locale, MESSAGES.gate().preLaunch().ready()),
                locale,
                launch,
                now);
    }

    /** Appends the countdown line in grey below a blank line, or nothing when there is no countdown. */
    private Component withCountdown(
            final Component screen, final Locale locale, final @Nullable Instant launch, final @Nullable Instant now) {
        if (now == null) {
            return screen;
        }
        return screen.appendNewline()
                .appendNewline()
                .append(LaunchCountdown.component(messages, locale, launch, now).color(NamedTextColor.GRAY));
    }

    /** A backend lost this player without a reason; shown on the redirect to the waiting room. */
    public Component connectionLost(final Locale locale) {
        return MessageRenderer.of(messages).format(locale, MESSAGES.gate().connectionLost());
    }

    /** The database is unreachable and the fallback cache has nothing usable for this player. */
    public Component trouble(final Locale locale) {
        return MessageRenderer.of(messages).format(locale, MESSAGES.gate().trouble());
    }

    /** The in-chat warning shown a few minutes before access runs out. */
    Component expiryWarning(final Locale locale, final long minutesRemaining) {
        return MessageRenderer.of(messages)
                .format(locale, MESSAGES.gate().expiry().warning(minutesRemaining));
    }

    /** The disconnect shown the moment access actually runs out mid-session. */
    Component expired(final Locale locale) {
        return MessageRenderer.of(messages)
                .format(locale, MESSAGES.gate().expiry().expired());
    }

    private boolean hasInvite() {
        return config.discordInviteUrl() != null && !config.discordInviteUrl().isBlank();
    }

    /** Renders a line in the fallback language with every other language loaded underneath, grey and italic. */
    static Component inEveryLanguage(final Messages messages, final MessageRef message) {
        final MessageRenderer renderer = MessageRenderer.of(messages);
        Component result = renderer.format(Locales.DEFAULT, message);
        for (final Locale other :
                messages.locales().subList(1, messages.locales().size())) {
            result = result.appendNewline()
                    .append(renderer.format(other, message)
                            .color(NamedTextColor.GRAY)
                            .decorate(TextDecoration.ITALIC));
        }
        return result;
    }
}
