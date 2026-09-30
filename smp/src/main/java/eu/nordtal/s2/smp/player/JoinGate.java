package eu.nordtal.s2.smp.player;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.database.access.FullServerAdmission;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
import io.papermc.paper.event.player.PlayerServerFullCheckEvent;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.slf4j.Logger;

/**
 * A login is refused while PostgreSQL is unreachable, and the players already on stay.
 *
 * The query that proves the database also warms the admin flag for protection and {@link FullServerAdmission}.
 */
public final class JoinGate implements Listener {

    private final Identities identities;
    private final FullServerAdmission admission;
    private final Messages messages;
    private final Logger logger;

    public JoinGate(
            final Identities identities,
            final FullServerAdmission admission,
            final Messages messages,
            final Logger logger) {
        this.identities = identities;
        this.admission = admission;
        this.messages = messages;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        try {
            final Identity identity = identities.load(event.getUniqueId());
            // Unconditionally, unlike limbo and hunger-games: the row is already read, closing the race window.
            admission.remember(event.getUniqueId(), identity.admin());
        } catch (final RuntimeException exception) {
            logger.error("refusing {}'s login because the database is unreachable", event.getUniqueId(), exception);
            // English: there is no account link yet to read a language from, which is itself the thing that is broken.
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    MessageRenderer.of(messages)
                            .format(Locale.ENGLISH, MESSAGES.smp().error().databaseUnreachable()));
        }
    }

    /**
     * Lets an admin onto a server Paper has decided is full, and past nothing but the cap.
     *
     * {@code HIGH} rather than {@code MONITOR}, because the answer has to be changed, not observed.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onFullCheck(final PlayerServerFullCheckEvent event) {
        if (event.isAllowed()) {
            return;
        }
        final UUID mcUuid = event.getPlayerProfile().getId();
        if (mcUuid != null && admission.admits(mcUuid)) {
            event.allow(true);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        identities.forget(event.getPlayer().getUniqueId());
        admission.forget(event.getPlayer().getUniqueId());
    }
}
