package eu.nordtal.s2.limbo.listener;

import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.FullServerAdmission;
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
 * {@link FullServerAdmission} carries the whole reasoning: one player limit for the network, written into this
 * server's {@code server.properties} from the same {@code .env} variable the proxy is given, and the proxy's admin
 * exemption rebuilt here so that it survives the change. It also carries the one thing about the ordering of these
 * two events that is assumed rather than proved.
 *
 * <b>Why this server, of all three</b>
 *
 * {@code limbo} is the one every login on the network crosses, so it is both the least likely to be full - players
 * are only here until their resource pack has applied - and the most expensive place to add a query per login.
 *
 * Every login pays for a query now, unconditionally: an admin is a server operator for the length of their session
 * ({@link eu.nordtal.s2.common.access.AdminOperators}), and the join handler that grants it runs on the main thread
 * and cannot query, so this is the one place allowed to read the flag. The gain is a cache warm for every player
 * rather than only during a burst, which is what makes the fullness answer below independent of how busy the server
 * was a tick ago.
 *
 * <b>What a failure here does, and does not, do</b>
 *
 * Nothing. A lookup that throws leaves the player un-warmed and Paper's own answer standing, which for everybody but
 * an admin at a full server is the right answer anyway. Refusing the login from here instead would replace a screen
 * that explains itself with one that does not.
 */
public final class FullServerGate implements Listener {

    private final AccessDirectory access;
    private final FullServerAdmission admission;
    private final Logger logger;

    public FullServerGate(final AccessDirectory access, final FullServerAdmission admission, final Logger logger) {
        this.access = Objects.requireNonNull(access, "access");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Reads the admin flag for every allowed login, on the one thread this server may wait on a database from.
     *
     * Every login, not only one close enough to the cap to change the answer: the flag is no longer only about a
     * full server, since an admin's operator grant for the length of their session depends on it and its own join
     * handler cannot query from the main thread it runs on.
     *
     * {@link FullServerAdmission#remember} is called either way, including with {@code false}: that is what keeps an
     * answer from an earlier connection out of this one.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        // One indexed query per login, on top of the language lookup that already happens here.
        boolean admin = false;
        try {
            admin = access.accessState(event.getUniqueId()).admin();
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
     * {@code PlayerServerFullCheckEvent} rather than {@code PlayerLoginEvent}: Paper 26.2 deprecated the latter and
     * names this one for exactly this purpose, because it decides without forcing the player entity into existence
     * first. A ban, a whitelist or any other refusal never reaches this event at all.
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
