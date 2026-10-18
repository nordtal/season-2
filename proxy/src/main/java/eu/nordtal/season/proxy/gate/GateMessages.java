package eu.nordtal.season.proxy.gate;

import static eu.nordtal.season.proxy.ProxyMessages.MESSAGES;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.proxy.config.GateSpec;
import eu.nordtal.season.proxy.launch.LaunchCountdown;
import java.time.Instant;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jspecify.annotations.Nullable;

/** Builds the screens and messages of the login gate, the expiry check and the phase router. */
public final class GateMessages {

    private final MessageRenderer renderer;
    private final GateSpec config;

    public GateMessages(final MessageRenderer renderer, final GateSpec config) {
        this.renderer = renderer;
        this.config = config;
    }

    /** The screen of an unlinked account, in English and under it in every other language. */
    Component notLinked(final String code, final @Nullable Instant launch, final @Nullable Instant now) {
        Component result = inEveryLanguage(renderer, MESSAGES.gate().notLinked(code));
        if (hasInvite()) {
            result = result.appendNewline()
                    .appendNewline()
                    .append(renderer.format(
                            Locale.ENGLISH, MESSAGES.gate().notLinkedSection().invite(config.discordInviteUrl())));
        }
        return withCountdown(result, Locale.ENGLISH, launch, now);
    }

    /** The screen for an account that is not linked any more. It has no link code. */
    public Component unlinked(final Locale locale) {
        return renderer.format(locale, MESSAGES.gate().unlinked());
    }

    /** The screen for a login while the proxy swap parks the network. */
    public Component restarting(final Locale locale) {
        return renderer.format(locale, MESSAGES.gate().restarting());
    }

    /** The screen for an account that is not a Discord member or is banned. */
    public Component notMember(final Locale locale) {
        Component result = renderer.format(locale, MESSAGES.gate().notMember());
        if (hasInvite()) {
            result = result.appendNewline()
                    .appendNewline()
                    .append(renderer.format(
                            locale, MESSAGES.gate().notMemberSection().invite(config.discordInviteUrl())));
        }
        return result;
    }

    /** The screen for a member without a running period. */
    public Component noAccess(final Locale locale) {
        Component result = renderer.format(locale, MESSAGES.gate().noAccess());
        if (hasInvite()) {
            result = result.appendNewline()
                    .appendNewline()
                    .append(renderer.format(
                            locale, MESSAGES.gate().noAccessSection().invite(config.discordInviteUrl())));
        }
        return result;
    }

    /** The screen for {@code MAINTENANCE} when {@code gate#server-limbo} names no registered server. */
    public Component maintenance(final Locale locale) {
        return renderer.format(locale, MESSAGES.gate().maintenance());
    }

    /** The screen for a phase whose server is not registered. */
    public Component noServer(final Locale locale) {
        return renderer.format(locale, MESSAGES.gate().noServer());
    }

    /** The screen for a network at the {@code max-players} of its players group. */
    Component full(final Locale locale, final int online, final int max) {
        return renderer.format(locale, MESSAGES.gate().full(online, max));
    }

    /** The screen for {@code PRE_LAUNCH} without a bought period. */
    public Component preLaunchBuy(final Locale locale, final @Nullable Instant launch, final @Nullable Instant now) {
        Component result = renderer.format(locale, MESSAGES.gate().preLaunch().buy());
        if (hasInvite()) {
            result = result.appendNewline()
                    .append(renderer.format(
                            locale, MESSAGES.gate().noAccessSection().invite(config.discordInviteUrl())));
        }
        return withCountdown(result, locale, launch, now);
    }

    /** The screen for {@code PRE_LAUNCH} with a bought period. */
    public Component preLaunchReady(final Locale locale, final @Nullable Instant launch, final @Nullable Instant now) {
        return withCountdown(renderer.format(locale, MESSAGES.gate().preLaunch().ready()), locale, launch, now);
    }

    /** Appends the countdown in grey below a blank line. Without a countdown it returns the screen. */
    private Component withCountdown(
            final Component screen, final Locale locale, final @Nullable Instant launch, final @Nullable Instant now) {
        if (now == null) {
            return screen;
        }
        return screen.appendNewline()
                .appendNewline()
                .append(LaunchCountdown.component(renderer, locale, launch, now).color(NamedTextColor.GRAY));
    }

    /** The message for a player whose backend connection ended without a reason. */
    public Component connectionLost(final Locale locale) {
        return renderer.format(locale, MESSAGES.gate().connectionLost());
    }

    /** The screen for a player when the database does not answer and the cache does not hold the player. */
    public Component trouble(final Locale locale) {
        return renderer.format(locale, MESSAGES.gate().trouble());
    }

    /** The chat warning before the period ends. */
    Component expiryWarning(final Locale locale, final long minutesRemaining) {
        return renderer.format(locale, MESSAGES.gate().expiry().warning(minutesRemaining));
    }

    /** The disconnect screen when the period ends. */
    Component expired(final Locale locale) {
        return renderer.format(locale, MESSAGES.gate().expiry().expired());
    }

    private boolean hasInvite() {
        return config.discordInviteUrl() != null && !config.discordInviteUrl().isBlank();
    }

    /** Renders the message in the default language and under it in every other language, grey and italic. */
    static Component inEveryLanguage(final MessageRenderer renderer, final MessageRef message) {
        Component result = renderer.format(Locales.DEFAULT, message);
        for (final Locale other :
                renderer.raw().locales().subList(1, renderer.raw().locales().size())) {
            result = result.appendNewline()
                    .append(renderer.format(other, message)
                            .color(NamedTextColor.GRAY)
                            .decorate(TextDecoration.ITALIC));
        }
        return result;
    }
}
