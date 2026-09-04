package eu.nordtal.s2.common.access;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is let onto a backend that is already full.
 *
 * <h2>Why a backend can be full at all, since 2026-09-04</h2>
 * It could not be, before: the three Paper backends carried {@code BACKEND_MAX_PLAYERS}, a number
 * set far above anything the network would hold, so that the proxy was the only thing that ever
 * refused a player. That bought one guarantee and paid for it with a second number - and the second
 * number is what every screen on a backend actually shows, because {@code Bukkit.getMaxPlayers()}
 * is what a tab list can reach. The network advertised 500 in the server browser and the tab list
 * said {@code 3/1000}, which is not a display bug but the two numbers being visible at once.
 *
 * <p>So there is one number now: {@code network.yml#max-players}, written into every backend's
 * {@code server.properties#max-players} from the same {@code .env} variable the proxy is given.
 * Nothing can drift, because nothing is derived.</p>
 *
 * <h2>What that costs, and what this class is</h2>
 * <b>Admins are exempt from the proxy's limit</b> - they are the people who have to come and fix a
 * full network - so a full network holds {@code max-players} plus whichever admins joined it, and
 * the backend they are routed to has to have room for them. With the backends on the same number,
 * Paper would refuse exactly those logins with <em>"Server full"</em>, after the admin had passed
 * the login gate and crossed {@code limbo}. That is the fault this repository has already shipped
 * twice, narrowed to the one player it hurts most.
 *
 * <p>The exemption is therefore rebuilt on the backend: Paper decides a login is full, a listener
 * looks the player up here, and an admin is allowed through. Bukkit's own bypass is not usable -
 * it reads {@code ops.json}, and an admin here is {@code discord_user.admin} in the database
 * (docs/smp.md#admins), which is the only admin list in this repository.</p>
 *
 * <h2>Which event, and why the answer is remembered rather than looked up</h2>
 * {@code io.papermc.paper.event.player.PlayerServerFullCheckEvent} is the one that can overturn a
 * full server, and it is the one Paper 26.2's deprecation of {@code PlayerLoginEvent} points at by
 * name: the older event works, but it forces the whole player entity into existence early, and
 * Paper warns about a plugin listening to it. The new one carries a profile rather than a player,
 * which is all this decision needs.
 *
 * <p>Nothing may query a database from it. It fires inside the login pipeline, Paper's own note
 * says the check runs <b>twice</b> for one login, and this repository has had no database round
 * trip on a login-critical path since 2026-09-01. {@code AsyncPlayerPreLoginEvent} runs earlier, on
 * a thread that is allowed to wait, so the flag is read there and read back here - the same shape
 * {@link eu.nordtal.s2.common.message.PlayerLocales} and {@code smp}'s {@code Identities} already
 * have. Reading is therefore not destructive: a check that fires twice must answer the same both
 * times.</p>
 *
 * <p>{@link #worthAsking(int, int)} is what keeps that from costing a query per login on servers
 * that are nowhere near full - which is every server, almost always, and {@code limbo} is the one
 * every login on the network crosses. A module that already holds the flag for other reasons
 * ({@code smp} does) should skip the check and {@link #remember} unconditionally: it is free there,
 * and it closes the window below.</p>
 *
 * <h2>The one assumption, stated because it is unverified</h2>
 * That the pre-login runs <em>before</em> the fullness check. It does in the pipeline as read from
 * Paper 26.2's sources - authentication, {@code AsyncPlayerPreLoginEvent}, then the login
 * validation the full check lives in - but nothing in this repository's tests can prove it, and if
 * it were the other way round an admin would be refused with the cache still empty and no log line
 * anywhere. The rehearsal that answers it is in the owner's checklist; the fallback if the answer
 * is no is to do the lookup inside the full check itself, which is allowed exactly as long as that
 * event turns out not to run on the main thread.
 *
 * <h2>The window, stated rather than fixed</h2>
 * Players can join between the pre-login and the check, so a login that was not near the cap when
 * it was considered can be refused when it arrives. {@link #HEADROOM} covers the ordinary version
 * of that; a burst larger than it costs an admin one reconnect, on which the server is now visibly
 * full and the flag is read. That is accepted for the same reason {@code network.yml#max-players}
 * accepts two simultaneous logins exceeding it by one: the fix is a reservation scheme for a state
 * nobody can observe.
 *
 * <h2>Lifetime</h2>
 * Only admins are held - {@link #remember} with {@code false} <em>removes</em> - so the map is at
 * most as large as the admin team, and a quit handler's {@link #forget} empties it in the ordinary
 * case. What can be left behind is an admin whose connection died between the pre-login and the
 * join; the cost of that entry is that their next connection would be admitted to a full server
 * without being looked up, which for an admin is the answer anyway. Calling {@link #remember} on
 * <b>every</b> pre-login, with whatever was found, is what keeps even that from outliving a
 * revoked flag.
 */
public final class FullServerAdmission {

    /**
     * How much room is left when a login is still close enough to the cap to be worth a query.
     *
     * <p>Five rather than zero because the count is read before the player is added and can move
     * before Paper checks it; five rather than fifty because every one of those is a database round
     * trip on a login path.</p>
     */
    public static final int HEADROOM = 5;

    private final Set<UUID> admins = ConcurrentHashMap.newKeySet();

    /**
     * Whether a login arriving now is close enough to the cap that the admin flag has to be read.
     *
     * @param online players on this server right now, the player logging in not counted
     * @param max    {@code Bukkit.getMaxPlayers()}
     */
    public static boolean worthAsking(final int online, final int max) {
        return online + HEADROOM >= max;
    }

    /**
     * Records what the pre-login thread found. Call it with {@code false} too - it is what makes a
     * stale entry from an earlier connection impossible.
     */
    public void remember(final UUID mcUuid, final boolean admin) {
        if (admin) {
            admins.add(mcUuid);
        } else {
            admins.remove(mcUuid);
        }
    }

    /**
     * Whether this login must be let through a server Paper considers full.
     *
     * <p><b>Does not consume.</b> Paper's own note on the deprecated {@code PlayerLoginEvent} says
     * the login validation runs twice for one login, so the fullness check can be asked twice - and
     * a reading that answered {@code true} then {@code false} would refuse the admin it had just
     * admitted, at a point where nothing is logged.</p>
     */
    public boolean admits(final UUID mcUuid) {
        return admins.contains(mcUuid);
    }

    public void forget(final UUID mcUuid) {
        admins.remove(mcUuid);
    }

    /** How many warmed answers are held; for tests and for a leak nobody expects. */
    public int size() {
        return admins.size();
    }
}
