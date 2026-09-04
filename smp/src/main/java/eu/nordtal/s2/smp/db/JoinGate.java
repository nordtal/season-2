package eu.nordtal.s2.smp.db;

import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.smp.player.Identities;

import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * What happens to a login when PostgreSQL is not there.
 *
 * <p>Decided 2026-09-01: <b>the join is refused, the people already playing stay.</b> Nobody is
 * thrown out of a fight over a ten-second blip, and nobody is let in to a session where their aura,
 * their language and their admin flag are all unknown - which is what the alternative looks like
 * from the inside, and it quietly produces wrong data rather than an error.
 *
 * <p>This handler runs on the async pre-login thread, which is the one place a Paper server is
 * <em>allowed</em> to wait on a database, and it does double duty: the same query that proves the
 * database is reachable also warms the admin flag for the protection listener, which cannot afford
 * to ask again on every block click.
 */
public final class JoinGate implements Listener {

    private final Identities identities;
    private final Messages messages;
    private final Logger logger;

    public JoinGate(final Identities identities, final Messages messages, final Logger logger) {
        this.identities = identities;
        this.messages = messages;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        try {
            identities.load(event.getUniqueId());
        } catch (final RuntimeException exception) {
            logger.error("refusing {}'s login because the database is unreachable",
                    event.getUniqueId(), exception);
            // English: at this point there is no account link to read a language from, which is
            // itself the thing that is broken.
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    MessageRenderer.of(messages).get(Locale.ENGLISH, "smp.error.database-unreachable"));
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        identities.forget(event.getPlayer().getUniqueId());
    }
}
