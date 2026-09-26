package eu.nordtal.s2.hungergames.listener;

import eu.nordtal.s2.common.access.FullServerAdmission;
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
 * Lets an admin onto this server when it is already full.
 *
 * {@link FullServerAdmission} carries the whole reasoning: one player limit for the network,
 * written into this server's {@code server.properties} from the same {@code .env} variable the
 * proxy is given, and the proxy's admin exemption rebuilt here so that it survives the change. It
 * also carries the one thing about the ordering of these two events that is assumed rather than
 * proved.
 *
 * Why this server, of all three: the start event is the one moment the network is deliberately all
 * in one place - every registered player is routed here at once, so this is the backend most likely
 * to actually sit at its cap, and it is the worst possible time for the admin who has to start the
 * game to be told the server is full.
 *
 * {@link FullServerAdmission#worthAsking} used to keep an ordinary login free of a query and pay
 * for it only during that burst. The flag is now read on every login instead, because an admin is a
 * server operator for the length of their session
 * ({@link eu.nordtal.s2.common.access.AdminOperators}) and the join handler that grants it runs on
 * the main thread and cannot query. It is one round trip on the pre-login thread, which is where
 * this module already does its waiting.
 *
 * What a failure here does, and does not, do: nothing. A lookup that throws leaves the player
 * un-warmed and Paper's own answer standing, which for everybody but an admin at a full server is
 * the right answer anyway. Refusing the login from here instead would replace a screen that
 * explains itself with one that does not.
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
     * Reads the admin flag, on the one thread this server is allowed to wait on a database from.
     *
     * Only for a login close enough to the cap that the answer could change anything.
     * {@link FullServerAdmission#remember} is called either way, including with {@code false}: that
     * is what keeps an answer from an earlier connection out of this one.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        // Read on every login: the main-thread join handler granting operator status cannot query it itself.
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
     * {@code PlayerServerFullCheckEvent} rather than {@code PlayerLoginEvent}: Paper 26.2 deprecated
     * the latter and names this one for exactly this purpose, because it decides without forcing the
     * player entity into existence first. A ban, a whitelist or any other refusal never reaches this
     * event at all.
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
