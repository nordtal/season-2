package eu.nordtal.s2.papercommon.access;

import eu.nordtal.s2.common.access.AdminOperators;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link AdminOperators.Ops} against the running server - the two Bukkit calls {@code :common}
 * cannot make itself, because it is compiled against no platform.
 *
 * <h2>Why this is a class and not three lambdas</h2>
 * It was three lambdas for about an hour on 2026-09-04, one per Paper plugin, and they were
 * character-for-character identical. That is what {@code :paper-common} exists to stop: {@code
 * :common} deliberately knows no platform, so anything the three plugins do identically <em>with</em>
 * a Paper type had nowhere to live but each plugin's own source tree, and the answer to "where does
 * shared Paper code go" had been "nowhere" since the repository was set up.
 *
 * <h2>The one decision inside it</h2>
 * {@code getOfflinePlayer(UUID)} rather than {@code getPlayer(UUID)}. A de-op has to work for
 * somebody who has already left, which is exactly what the quit handler asks for - and
 * {@code getPlayer} answers {@code null} for them, so the operator would survive the session that
 * granted it and wait in {@code ops.json} for the enable sweep to find it. It would be found, which
 * is why this would have been a slow bug rather than a loud one.
 */
public final class BukkitOps implements AdminOperators.Ops {

    @Override
    public void setOp(final UUID player, final boolean operator) {
        Bukkit.getOfflinePlayer(player).setOp(operator);
    }

    @Override
    public Set<UUID> operators() {
        return Bukkit.getOperators().stream()
                .map(OfflinePlayer::getUniqueId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The applier every Paper plugin builds at enable, over the running server. */
    public static AdminOperators create() {
        return new AdminOperators(new BukkitOps());
    }
}
