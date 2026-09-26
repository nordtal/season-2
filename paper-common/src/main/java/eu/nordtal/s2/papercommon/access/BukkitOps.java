package eu.nordtal.s2.papercommon.access;

import eu.nordtal.s2.common.access.AdminOperators;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * {@link AdminOperators.Ops} against the running server - the two Bukkit calls {@code :common}
 * cannot make itself, because it is compiled against no platform.
 *
 * One class rather than one lambda per Paper plugin: {@code :common} deliberately knows no
 * platform, so anything the plugins do identically <em>with</em> a Paper type belongs here instead
 * of being duplicated in each plugin's own source tree.
 *
 * {@code getOfflinePlayer(UUID)} rather than {@code getPlayer(UUID)}: a de-op has to work for
 * somebody who has already left, which is exactly what the quit handler asks for, and
 * {@code getPlayer} answers {@code null} for them - the operator would otherwise survive the
 * session that granted it and wait in {@code ops.json} for the enable sweep to find it.
 */
public final class BukkitOps implements AdminOperators.Ops {

    @Override
    public void setOp(final UUID player, final boolean operator) {
        Bukkit.getOfflinePlayer(player).setOp(operator);
    }

    @Override
    public Set<UUID> operators() {
        return Bukkit.getOperators().stream().map(OfflinePlayer::getUniqueId).collect(Collectors.toUnmodifiableSet());
    }

    /** The applier every Paper plugin builds at enable, over the running server. */
    public static AdminOperators create() {
        return new AdminOperators(new BukkitOps());
    }
}
