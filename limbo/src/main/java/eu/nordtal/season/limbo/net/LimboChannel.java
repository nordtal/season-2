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
 * The limbo end of {@code nordtal:limbo}.
 * It shows the reason of each {@code WAIT} from the proxy and sends {@code READY}.
 */
public final class LimboChannel implements PluginMessageListener {

    private final Plugin plugin;
    private final WaitingRoom room;

    public LimboChannel(final Plugin plugin, final WaitingRoom room) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.room = Objects.requireNonNull(room, "room");
    }

    /** Registers the channel in both directions. */
    public void register() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, LimboProtocol.CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, LimboProtocol.CHANNEL, this);
    }

    /** Unregisters the channel in both directions. */
    public void unregister() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, LimboProtocol.CHANNEL);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, LimboProtocol.CHANNEL, this);
    }

    /** The interval at which {@code READY} is sent again while the player is here. */
    public static final Duration READY_REPEAT = Duration.ofSeconds(1);

    /** Sends {@code READY} for the player. */
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
            final WaitReason reason = Objects.requireNonNull(decoded.get().reason(), "reason");
            room.show(player, reason, decoded.get().percent());
            return;
        }

        plugin.getLogger().warning("Ignored a READY on " + LimboProtocol.CHANNEL + ", which only this server sends");
    }
}
