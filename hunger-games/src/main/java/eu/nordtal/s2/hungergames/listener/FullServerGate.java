package eu.nordtal.s2.hungergames.listener;

import eu.nordtal.s2.database.access.FullServerAdmission;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import io.papermc.paper.event.player.PlayerServerFullCheckEvent;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.slf4j.Logger;

/**
 * Lets an admin onto this server when it is already full, and reads every login's admin flag.
 *
 * A failed lookup leaves Paper's own answer standing; {@link FullServerAdmission} has the reasoning.
 */
public final class FullServerGate implements Listener {

    private final HungerGamesDao dao;
    private final FullServerAdmission admission;
    private final Logger logger;

    public FullServerGate(final HungerGamesDao dao, final FullServerAdmission admission, final Logger logger) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Reads the admin flag for every allowed login, on the one thread this server may wait on a database from.
     *
     * Every login, since the operator grant needs it; {@code false} is remembered too, clearing an earlier answer.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        boolean admin = false;
        try {
            admin = dao.isAdmin(event.getUniqueId()).orElse(Boolean.FALSE);
        } catch (final RuntimeException exception) {
            logger.warn(
                    "could not read whether {} is an admin, so they get neither operator nor a"
                            + " place on a full server",
                    event.getUniqueId(),
                    exception);
        }
        admission.remember(event.getUniqueId(), admin);
    }

    /**
     * Overturns the fullness check, and only that one.
     *
     * Paper 26.2 names {@code PlayerServerFullCheckEvent} for this; it decides without creating the player entity.
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
        admission.forget(event.getPlayer().getUniqueId());
    }
}
