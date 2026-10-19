package eu.nordtal.season.proxy.gate;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.value.Example;
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

    record Code(@Example("K7Q2MX") String code) {}

    /** @param invite the Discord invite, {@code null} without one */
    record Invite(
            @Example("https://discord.gg/nordtal") @Nullable String invite) {}

    record Full(@Example("100") int online, @Example("100") int max) {}

    record Minutes(@Example("5") long minutes) {}

    static final DocumentSpec<Code> NOT_LINKED = screen("not-linked", Code.class);
    /** Under the screen in every language, in English. */
    static final DocumentSpec<Invite> NOT_LINKED_INVITE = screen("not-linked-invite", Invite.class);

    static final DocumentSpec<DocumentSpec.None> UNLINKED = screen("unlinked", DocumentSpec.None.class);
    static final DocumentSpec<DocumentSpec.None> RESTARTING = screen("restarting", DocumentSpec.None.class);
    static final DocumentSpec<Invite> NOT_MEMBER = screen("not-member", Invite.class);
    static final DocumentSpec<Invite> NO_ACCESS = screen("no-access", Invite.class);
    static final DocumentSpec<DocumentSpec.None> MAINTENANCE = screen("maintenance", DocumentSpec.None.class);
    static final DocumentSpec<DocumentSpec.None> NO_SERVER = screen("no-server", DocumentSpec.None.class);
    static final DocumentSpec<DocumentSpec.None> TROUBLE = screen("trouble", DocumentSpec.None.class);
    static final DocumentSpec<Full> FULL = screen("full", Full.class);
    static final DocumentSpec<Invite> PRE_LAUNCH_BUY = screen("pre-launch-buy", Invite.class);
    static final DocumentSpec<DocumentSpec.None> PRE_LAUNCH_READY = screen("pre-launch-ready", DocumentSpec.None.class);
    static final DocumentSpec<DocumentSpec.None> EXPIRED = screen("expired", DocumentSpec.None.class);
    /** The chat line of a player whom a lost backend sends to the waiting room. */
    static final DocumentSpec<DocumentSpec.None> CONNECTION_LOST =
            DocumentSpec.game("proxy", "connection-lost", "Login", DocumentSpec.Shape.CHAT);

    static final DocumentSpec<Minutes> EXPIRY_WARNING =
            DocumentSpec.game("proxy", "expiry-warning", "Login", DocumentSpec.Shape.CHAT, Minutes.class);

    private final MessageRenderer renderer;
    private final GateSpec config;

    public GateMessages(final MessageRenderer renderer, final GateSpec config) {
        this.renderer = renderer;
        this.config = config;
    }

    /** The screen of an unlinked account, in English and under it in every other language. */
    Component notLinked(final String code, final @Nullable Instant launch, final @Nullable Instant now) {
        Component result = inEveryLanguage(renderer, NOT_LINKED.with(new Code(code)));
        if (invite().invite() != null) {
            result = result.appendNewline()
                    .appendNewline()
                    .append(renderer.format(Locale.ENGLISH, NOT_LINKED_INVITE.with(invite())));
        }
        return withCountdown(result, Locale.ENGLISH, launch, now);
    }

    /** The screen for an account that is not linked any more. It has no link code. */
    public Component unlinked(final Locale locale) {
        return renderer.format(locale, Sent.of(UNLINKED));
    }

    /** The screen for a login while the proxy swap parks the network. */
    public Component restarting(final Locale locale) {
        return renderer.format(locale, Sent.of(RESTARTING));
    }

    /** The screen for an account that is not a Discord member or is banned. */
    public Component notMember(final Locale locale) {
        return renderer.format(locale, NOT_MEMBER.with(invite()));
    }

    /** The screen for a member without a running period. */
    public Component noAccess(final Locale locale) {
        return renderer.format(locale, NO_ACCESS.with(invite()));
    }

    /** The screen for {@code MAINTENANCE} when {@code gate#server-limbo} names no registered server. */
    public Component maintenance(final Locale locale) {
        return renderer.format(locale, Sent.of(MAINTENANCE));
    }

    /** The screen for a phase whose server is not registered. */
    public Component noServer(final Locale locale) {
        return renderer.format(locale, Sent.of(NO_SERVER));
    }

    /** The screen for a network at the {@code max-players} of its players group. */
    Component full(final Locale locale, final int online, final int max) {
        return renderer.format(locale, FULL.with(new Full(online, max)));
    }

    /** The screen for {@code PRE_LAUNCH} without a bought period. */
    public Component preLaunchBuy(final Locale locale, final @Nullable Instant launch, final @Nullable Instant now) {
        return withCountdown(renderer.format(locale, PRE_LAUNCH_BUY.with(invite())), locale, launch, now);
    }

    /** The screen for {@code PRE_LAUNCH} with a bought period. */
    public Component preLaunchReady(final Locale locale, final @Nullable Instant launch, final @Nullable Instant now) {
        return withCountdown(renderer.format(locale, Sent.of(PRE_LAUNCH_READY)), locale, launch, now);
    }

    /** Appends the countdown in grey below a blank line. Without a countdown it returns the screen. */
    private Component withCountdown(
            final Component screen, final Locale locale, final @Nullable Instant launch, final @Nullable Instant now) {
        if (now == null) {
            return screen;
        }
        return screen.appendNewline()
                .appendNewline()
                .append(renderer.format(locale, LaunchCountdown.line(launch, now))
                        .color(NamedTextColor.GRAY));
    }

    /** The message for a player whose backend connection ended without a reason. */
    public Component connectionLost(final Locale locale) {
        return renderer.format(locale, Sent.of(CONNECTION_LOST));
    }

    /** The screen for a player when the database does not answer and the cache does not hold the player. */
    public Component trouble(final Locale locale) {
        return renderer.format(locale, Sent.of(TROUBLE));
    }

    /** The chat warning before the period ends. */
    Component expiryWarning(final Locale locale, final long minutesRemaining) {
        return renderer.format(locale, EXPIRY_WARNING.with(new Minutes(minutesRemaining)));
    }

    /** The disconnect screen when the period ends. */
    Component expired(final Locale locale) {
        return renderer.format(locale, Sent.of(EXPIRED));
    }

    /** Returns the configured invite, {@code null} when it is blank. */
    private Invite invite() {
        final String url = config.discordInviteUrl();
        return new Invite(url == null || url.isBlank() ? null : url);
    }

    /** Renders the document in the default language and under it in every other language, grey and italic. */
    static Component inEveryLanguage(final MessageRenderer renderer, final Sent<?> sent) {
        Component result = renderer.format(Locales.DEFAULT, sent);
        for (final Locale other :
                renderer.raw().locales().subList(1, renderer.raw().locales().size())) {
            result = result.appendNewline()
                    .append(renderer.format(other, sent)
                            .color(NamedTextColor.GRAY)
                            .decorate(TextDecoration.ITALIC));
        }
        return result;
    }

    private static <V extends Record> DocumentSpec<V> screen(final String name, final Class<V> values) {
        return DocumentSpec.game("proxy", name, "Login", DocumentSpec.Shape.KICK_SCREEN, values);
    }
}
