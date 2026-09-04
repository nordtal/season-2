package eu.nordtal.s2.smp.db;

import eu.nordtal.s2.common.access.FullServerAdmission;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.player.Identity;

import io.papermc.paper.event.player.PlayerServerFullCheckEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.UUID;

/**
 * What happens to a login when PostgreSQL is not there.
 *
 * <p>Decided 2026-09-01: <b>the join is refused, the people already playing stay.</b> Nobody is
 * thrown out of a fight over a ten-second blip, and nobody is let in to a session where their aura,
 * their language and their admin flag are all unknown - which is what the alternative looks like
 * from the inside, and it quietly produces wrong data rather than an error.
 *
 * <p>This handler runs on the async pre-login thread, which is the one place a Paper server is
 * <em>allowed</em> to wait on a database, and it does triple duty: the same query that proves the
 * database is reachable also warms the admin flag for the protection listener, which cannot afford
 * to ask again on every block click, and for {@link FullServerAdmission}, which is asked on the
 * main thread and so cannot query at all.
 *
 * <h2>The full server</h2>
 * Since 2026-09-04 this server's {@code max-players} is the network's own limit rather than a
 * number set out of reach, so Paper can refuse a login for fullness - and the login it would refuse
 * is an admin's, because admins are the only players the proxy lets past a full network.
 * {@link #onFullCheck} is what overturns that; {@link FullServerAdmission} carries the reasoning,
 * including why it is Paper's own {@code PlayerServerFullCheckEvent} and not the deprecated
 * {@code PlayerLoginEvent}. Warming it costs nothing here, because {@link Identities#load} has
 * already read the flag.
 */
public final class JoinGate implements Listener {

    private final Identities identities;
    private final FullServerAdmission admission;
    private final Messages messages;
    private final Logger logger;

    public JoinGate(final Identities identities, final FullServerAdmission admission,
                    final Messages messages, final Logger logger) {
        this.identities = identities;
        this.admission = admission;
        this.messages = messages;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        try {
            final Identity identity = identities.load(event.getUniqueId());
            // Unconditionally, unlike limbo and hunger-games: the row is already read, so there is
            // no query to save by asking FullServerAdmission#worthAsking first - and warming every
            // login closes the window between this thread and PlayerLoginEvent outright.
            admission.remember(event.getUniqueId(), identity.admin());
        } catch (final RuntimeException exception) {
            logger.error("refusing {}'s login because the database is unreachable",
                    event.getUniqueId(), exception);
            // English: at this point there is no account link to read a language from, which is
            // itself the thing that is broken.
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    MessageRenderer.of(messages).get(Locale.ENGLISH, "smp.error.database-unreachable"));
        }
    }

    /**
     * Lets an admin onto a server Paper has decided is full.
     *
     * <p>{@code HIGH} rather than {@code MONITOR}: the answer has to be changed, not observed. It
     * is as narrow as an event can be - a ban, a whitelist or another plugin's refusal never
     * reaches the fullness check at all, so an admin is never let past anything but the cap.</p>
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
