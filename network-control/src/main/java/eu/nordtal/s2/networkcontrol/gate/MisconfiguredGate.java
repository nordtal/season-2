package eu.nordtal.s2.networkcontrol.gate;

import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;

import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import org.slf4j.Logger;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The per-plugin disable Velocity does not have: a {@code LoginEvent} handler that refuses
 * <b>everybody</b> because {@code network-control}'s own configuration could not be read.
 *
 * <p>Failing closed is the point. "The proxy is up but nobody can join" announces itself within
 * seconds of the first player trying; "the proxy is up and the gate is off" announces itself never,
 * and a single mistyped key would silently open the network.</p>
 *
 * <p>Admins cannot be exempted: the admin flag is a column in the database a broken
 * {@code database.yml} is the reason we cannot reach, and an exemption would mean inventing a second
 * notion of who is an admin inside the file that is itself broken. The recovery path is a human
 * fixing the file and restarting the proxy.</p>
 *
 * <p>The screen is bilingual because the table that stores every player's language is unreachable.
 * It also answers the ping, since the MOTD lives in {@code network.yml} and that is one of the files
 * that can be broken - the line comes from the message bundle, a classpath resource and therefore
 * the one thing still readable when the configuration is the problem.</p>
 */
public final class MisconfiguredGate {

    private final Logger logger;
    private final Component screen;
    private final Component motd;

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
        this.screen = MessageRenderer.of(messages).get(Locale.ENGLISH, "gate.misconfigured")
                .appendNewline()
                .append(MessageRenderer.of(messages).get(Locale.GERMAN, "gate.misconfigured")
                        .color(NamedTextColor.GRAY)
                        .decorate(TextDecoration.ITALIC));
        // English only: a ping carries no player, so there is no language to pick.
        this.motd = MiniMessage.miniMessage().deserialize(messages.get(Locale.ENGLISH, "motd.misconfigured"));
    }

    /**
     * What the server browser shows while nobody can join. Deliberately not the configured MOTD -
     * the configuration is what failed.
     */
    @Subscribe
    public void onPing(final ProxyPingEvent event) {
        event.setPing(event.getPing().asBuilder()
                .description(motd)
                .build());
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
     * Package-visible so a test can assert it without constructing a {@code LoginEvent} and a
     * {@code Player}, neither of which exists outside a running proxy.
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

    /** Keeps a busy proxy in this state from writing one error line per join attempt. */
    private static final int REPEAT_LOG_EVERY = 25;
}
