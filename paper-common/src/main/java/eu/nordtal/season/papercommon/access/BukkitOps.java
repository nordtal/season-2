package eu.nordtal.season.papercommon.access;

import eu.nordtal.season.database.access.AdminOperators;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * {@link AdminOperators.Ops} on the running server.
 * It uses {@code getOfflinePlayer}, because the quit handler removes the operator after the player left.
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

    public static AdminOperators create() {
        return new AdminOperators(new BukkitOps());
    }
}
