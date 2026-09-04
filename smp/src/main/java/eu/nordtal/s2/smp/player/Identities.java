package eu.nordtal.s2.smp.player;

import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.smp.db.IdentityRow;
import eu.nordtal.s2.smp.db.SmpDao;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who everybody online is, read once per join and kept until they leave.
 *
 * <p>The composition is redrawn on every tab-list refresh, every chat line and every nametag update,
 * so the alternative to this cache is a database round trip inside a render loop. That is the
 * main-thread mistake this repository has already made twice - once in {@code hunger-games}' join
 * handler and once in {@code /hg start} - and the rule since 2026-09-01 has no exceptions.
 *
 * <p>The load happens on the async pre-login thread, which is where the database is allowed to be
 * slow. Aura changes are written through {@link #recordAura} by whoever changed it rather than
 * re-read, because the writer already knows the new value.
 */
public final class Identities {

    private final SmpDao dao;
    private final Map<UUID, Identity> byPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, String> discordIds = new ConcurrentHashMap<>();

    public Identities(final SmpDao dao) {
        this.dao = dao;
    }

    /**
     * Reads one player's whole composition. <b>Blocking - never call this on the main thread.</b>
     *
     * <p>A player with no account link gets {@link Identity#unknown}: they should not have got past
     * the proxy's gate at all, so this is defence against a state that means something else is
     * already wrong, not a supported way to play.
     */
    public Identity load(final UUID mcUuid) {
        dao.discordIdOf(mcUuid).ifPresent(id -> discordIds.put(mcUuid, id));

        final Optional<IdentityRow> row = dao.identityOf(mcUuid);
        final Identity identity = row
                .map(r -> new Identity(
                        Locales.parse(r.locale()),
                        Boolean.TRUE.equals(r.admin()),
                        Boolean.TRUE.equals(r.donor()),
                        r.aura() == null ? 0 : r.aura(),
                        r.playtimeSeconds() == null ? 0L : r.playtimeSeconds()))
                .orElseGet(() -> Identity.unknown(Locales.DEFAULT));

        byPlayer.put(mcUuid, identity);
        return identity;
    }

    /** What is known about a player right now, or a neutral placeholder while the load is in flight. */
    public Identity of(final UUID mcUuid) {
        return byPlayer.getOrDefault(mcUuid, Identity.unknown(Locales.DEFAULT));
    }

    public Optional<String> discordIdOf(final UUID mcUuid) {
        return Optional.ofNullable(discordIds.get(mcUuid));
    }

    /**
     * Re-derives everybody's admin flag from the authoritative set.
     *
     * <p>Called by the admin watcher on every notification and every poll tick. The whole set is
     * handed in rather than a delta, for the reason {@code AdminWatch} gives: a notification is
     * never trusted as state, so a lost one costs latency and not correctness.</p>
     *
     * @param admins every admin's Minecraft account, freshly read
     * @return whether any cached flag actually changed - the caller redraws the six-element
     *         composition only then, because a redraw of every surface on a thirty-second timer for
     *         the life of the season is exactly the kind of work that is invisible until it is not
     */
    public boolean recordAdmins(final java.util.Set<UUID> admins) {
        boolean changed = false;
        for (final Map.Entry<UUID, Identity> entry : byPlayer.entrySet()) {
            final boolean isAdmin = admins.contains(entry.getKey());
            if (entry.getValue().admin() != isAdmin) {
                byPlayer.computeIfPresent(entry.getKey(), (uuid, identity) -> identity.withAdmin(isAdmin));
                changed = true;
            }
        }
        return changed;
    }

    /** Updates the cached aura after somebody else has written it. */
    public void recordAura(final UUID mcUuid, final int aura) {
        byPlayer.computeIfPresent(mcUuid, (uuid, identity) -> identity.withAura(aura));
    }

    public void forget(final UUID mcUuid) {
        byPlayer.remove(mcUuid);
        discordIds.remove(mcUuid);
    }

    public int size() {
        return byPlayer.size();
    }
}
