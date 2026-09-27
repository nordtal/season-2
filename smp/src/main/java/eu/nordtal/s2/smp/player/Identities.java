package eu.nordtal.s2.smp.player;

import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.smp.db.IdentityRow;
import eu.nordtal.s2.smp.db.SmpDao;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who everybody online is, read once at async pre-login and kept until they leave.
 *
 * Render loops read it and must never cost a query; aura is written through {@link #recordAura}.
 */
public final class Identities {

    private final SmpDao dao;
    private final Map<UUID, Identity> byPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, String> discordIds = new ConcurrentHashMap<>();

    public Identities(final SmpDao dao) {
        this.dao = dao;
    }

    /**
     * Reads one player's whole composition, blocking, so never on the main thread.
     *
     * A player with no account link gets {@link Identity#unknown}, as a defence and not a way to play.
     */
    public Identity load(final UUID mcUuid) {
        dao.discordIdOf(mcUuid).ifPresent(id -> discordIds.put(mcUuid, id));

        final Optional<IdentityRow> row = dao.identityOf(mcUuid);
        final Identity identity = row.map(r -> new Identity(
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
     * Re-derives everybody's admin flag from the whole authoritative set, on every notification and poll.
     *
     * @param admins every admin's Minecraft account, freshly read
     * @return whether any cached flag changed, so the caller redraws only then
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
