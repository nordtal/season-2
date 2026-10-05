package eu.nordtal.season.limbo.net;

import eu.nordtal.season.limbo.waiting.WaitingRoom;
import eu.nordtal.season.limboprotocol.LimboProtocol;
import eu.nordtal.season.limboprotocol.WaitReason;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * This module's end of {@code nordtal:limbo}: it listens for the proxy's {@code WAIT} and answers {@code READY}.
 *
 * READY carries no destination, so routing stays in the proxy, which drops every client-sent message here.
 */
public final class LimboChannel implements PluginMessageListener {

    private final Plugin plugin;
    private final WaitingRoom room;

    public LimboChannel(final Plugin plugin, final WaitingRoom room) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.room = Objects.requireNonNull(room, "room");
    }

    /** Registers both directions of the channel with Bukkit's messenger. */
    public void register() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, LimboProtocol.CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, LimboProtocol.CHANNEL, this);
    }

    /** Unregisters both directions. */
    public void unregister() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, LimboProtocol.CHANNEL);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, LimboProtocol.CHANNEL, this);
    }

    /** How often READY is repeated while the player is still here: every second. */
    public static final Duration READY_REPEAT = Duration.ofSeconds(1);

    /**
     * Tells the proxy this player is ready to be routed on.
     *
     * Sent a tick after the join and every {@link #READY_REPEAT}, since Velocity can drop one; it is idempotent.
     */
    public void sendReady(final Player player) {
        player.sendPluginMessage(plugin, LimboProtocol.CHANNEL, LimboProtocol.ready());
    }

    @Override
    public void onPluginMessageReceived(final String channel, final Player player, final byte[] message) {
        if (!LimboProtocol.CHANNEL.equals(channel)) {
            return;
        }

        final Optional<LimboProtocol.Message> decoded = LimboProtocol.decode(message);
        if (decoded.isEmpty()) {
            plugin.getLogger()
                    .warning("Dropped an unreadable " + LimboProtocol.CHANNEL + " message for " + player.getName());
            return;
        }
        if (decoded.get().type() != LimboProtocol.Type.READY) {
            // WAIT is the only other type, and LimboProtocol.Message guarantees it always carries a reason.
            final WaitReason reason = Objects.requireNonNull(decoded.get().reason(), "reason");
            room.show(player, reason);
            return;
        }

        // READY runs limbo -> proxy only; one arriving here is a bug in whatever sent it, not something to act on.
        plugin.getLogger().warning("Ignored a READY on " + LimboProtocol.CHANNEL + ", which only this server sends");
    }
}
