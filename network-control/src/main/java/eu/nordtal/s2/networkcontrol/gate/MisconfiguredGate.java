package eu.nordtal.s2.networkcontrol.gate;

import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;

import eu.nordtal.s2.common.message.Messages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.slf4j.Logger;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The plugin disable Velocity does not have: a {@code LoginEvent} handler that refuses
 * <b>everybody</b> because {@code network-control}'s own configuration could not be read.
 *
 * <h2>Why this exists</h2>
 * docs/architecture.md#failing-closed-on-a-bad-config, settled 2026-08-31. Until then a bad
 * {@code database.yml} or {@code gate.yml} was logged loudly and the login gate was simply never
 * registered - so the proxy kept running and kept accepting logins <b>un-gated</b>. That is the
 * wrong way round for a value whose whole job is deciding who may join: <i>"the proxy is up but
 * nobody can join"</i> announces itself within seconds of the first player trying, while <i>"the
 * proxy is up and the gate is off"</i> announces itself never, and a single mistyped key silently
 * opens the network.
 * <p>
 * The objection the old behaviour was justified with - Velocity has no per-plugin disable - is true
 * and beside the point. This class is that disable, built by hand, and it costs one class.
 * </p>
 *
 * <h2>Admins are not exempted, and cannot be</h2>
 * There is nobody to exempt. The admin flag is a column on {@code discord_user}, in the database
 * that a broken {@code database.yml} is the reason we cannot reach. An exemption here would have to
 * invent a second notion of who is an admin - a UUID list in a config file that is itself the thing
 * that is broken - which is exactly the design docs/season-phases.md#how-an-admin-is-recognised
 * rejects. The recovery path is a human fixing the file and restarting the proxy; that is the whole
 * of it, on purpose.
 *
 * <h2>The screen is bilingual</h2>
 * For a stronger version of the reason the unlinked screen is: not only is the player unidentified,
 * the table that stores every player's language is unreachable. English first, German underneath in
 * grey italics, exactly like {@link GateMessages#notLinked(String)}.
 */
public final class MisconfiguredGate {

    private final Logger logger;
    private final Component screen;

    /** How many logins have been refused, so the log line can say "and 400 others" rather than 400 lines. */
    private final AtomicLong refused = new AtomicLong();

    /**
     * @param logger   the plugin logger
     * @param messages the bundle; loading it needs no configuration, only the classpath, which is
     *                 what makes a translated screen possible on a path where nothing else works
     */
    public MisconfiguredGate(final Logger logger, final Messages messages) {
        this.logger = Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(messages, "messages");
        this.screen = Component.text(messages.get(Locale.ENGLISH, "gate.misconfigured"))
                .appendNewline()
                .append(Component.text(messages.get(Locale.GERMAN, "gate.misconfigured"))
                        .color(NamedTextColor.GRAY)
                        .decorate(TextDecoration.ITALIC));
    }

    @Subscribe
    public void onLogin(final LoginEvent event) {
        event.setResult(ComponentResult.denied(
                refuse(event.getPlayer().getUniqueId(), event.getPlayer().getUsername())));
    }

    /**
     * The decision itself, without the Velocity event around it: refuse, count, and log the first
     * one and every {@value #REPEAT_LOG_EVERY}th after that.
     * <p>
     * Package-visible so that a test can prove "everybody is refused, admin or not" without
     * constructing a {@code LoginEvent} and a {@code Player}, neither of which exists outside a
     * running proxy. There is no state to vary here anyway - that is the point of this class.
     * </p>
     *
     * @param mcUuid   who tried
     * @param username their name, for the log line
     * @return the screen they get, which is the same screen every time
     */
    Component refuse(final UUID mcUuid, final String username) {
        final long count = refused.incrementAndGet();
        if (count == 1 || count % REPEAT_LOG_EVERY == 0) {
            logger.error("Refused {} ({}) - network-control is misconfigured, so NOBODY is being "
                            + "let in. Fix the configuration and restart the proxy. ({} refused so far)",
                    mcUuid, username, count);
        }
        return screen;
    }

    /** @return how many logins have been refused by this handler, for tests and for the shutdown log */
    public long refusedCount() {
        return refused.get();
    }

    /**
     * A busy proxy in this state would otherwise write one error line per join attempt for as long
     * as it takes somebody to notice. The first is always logged, because that is the one that has
     * to reach whoever is watching.
     */
    private static final int REPEAT_LOG_EVERY = 25;
}
