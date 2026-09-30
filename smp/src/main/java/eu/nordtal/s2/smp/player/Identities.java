package eu.nordtal.s2.smp.player;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.database.access.PlayerIdentity;
import eu.nordtal.s2.smp.db.SmpDao;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who everybody online is, as the six-element composition draws it: the base's identity plus the aura.
 *
 * The aura is this plugin's part of the base's session cache, read and dropped with it; render loops never query.
 */
public final class Identities implements eu.nordtal.s2.papercommon.player.Identities.Part {

    private final eu.nordtal.s2.papercommon.player.Identities base;
    private final SmpDao dao;
    private final Map<PlayerId, Integer> auras = new ConcurrentHashMap<>();

    /** Attaches itself to {@code base}, so every identity read from now on carries its aura. */
    public Identities(final eu.nordtal.s2.papercommon.player.Identities base, final SmpDao dao) {
        this.base = Objects.requireNonNull(base, "base");
        this.dao = Objects.requireNonNull(dao, "dao");
        base.attach(this);
    }

    @Override
    public void load(final PlayerIdentity identity) {
        final DiscordId discordId = identity.discordId();
        final int aura = discordId == null ? 0 : dao.auraOf(discordId).orElse(0);
        auras.put(identity.player(), aura);
    }

    @Override
    public void forget(final PlayerId player) {
        auras.remove(player);
    }

    /** What is known about a player right now, or a neutral placeholder while nothing is held. */
    public Identity of(final UUID mcUuid) {
        final PlayerId player = PlayerId.of(mcUuid);
        final PlayerIdentity identity = base.of(player);
        return new Identity(
                identity.locale(),
                identity.admin(),
                identity.donor(),
                auras.getOrDefault(player, 0),
                identity.playtimeSeconds());
    }

    public Optional<DiscordId> discordIdOf(final UUID mcUuid) {
        return base.discordIdOf(PlayerId.of(mcUuid));
    }

    /** Updates the held aura after somebody else has written it. */
    public void recordAura(final UUID mcUuid, final int aura) {
        auras.computeIfPresent(PlayerId.of(mcUuid), (player, held) -> aura);
    }
}
