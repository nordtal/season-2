package eu.nordtal.s2.limbo.net;

import eu.nordtal.s2.common.limbo.LimboProtocol;
import eu.nordtal.s2.common.limbo.WaitReason;
import eu.nordtal.s2.limbo.waiting.WaitingRoom;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * This module's end of {@code nordtal:limbo}: it listens for the proxy's {@code WAIT} and answers
 * {@code READY} once per join.
 *
 * <h2>What READY means, and what it does not</h2>
 * "This player has arrived and finished joining me." <b>Not</b> "send them to the SMP", and not
 * anything about where they should go - docs/season-phases.md#routing is explicit that "a backend
 * must not be able to decide it wants a player somewhere - that would put the routing rules in two
 * processes". The message carries no destination and no room for one; the proxy works out where the
 * player belongs from the phase, exactly as it does when a phase change moves everybody.
 *
 * <h2>Trusting what arrives</h2>
 * On this side of the connection a plugin message from the proxy and one from the player's own
 * client are the same thing, and neither is distinguishable from the other. What makes that safe is
 * on the <em>proxy</em>: {@code PackStation} consumes every {@code nordtal:limbo} message a client
 * sends ({@code ForwardResult.handled()}) rather than forwarding it, so nothing a client writes
 * reaches this listener. The worst a forged {@code WAIT} could do here anyway is put the wrong
 * title on the forger's own screen - the release decision is not made in this process at all.
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
        plugin.getServer().getMessenger()
                .registerIncomingPluginChannel(plugin, LimboProtocol.CHANNEL, this);
    }

    /** Unregisters both directions. */
    public void unregister() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, LimboProtocol.CHANNEL);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, LimboProtocol.CHANNEL, this);
    }

    /**
     * Tells the proxy this player is ready to be routed on.
     * <p>
     * Sent one tick after the join rather than inside it, for two reasons: the player's connection
     * is unambiguously established by then, and it puts a hard ordering between "this server has
     * the player" and "the proxy may move them" that does not depend on how Bukkit happens to order
     * two handlers of the same event.
     * </p>
     *
     * @param player the player who has just arrived
     */
    public void sendReady(final Player player) {
        player.sendPluginMessage(plugin, LimboProtocol.CHANNEL, LimboProtocol.ready());
    }

    @Override
    public void onPluginMessageReceived(final @NotNull String channel, final @NotNull Player player,
                                        final byte @NotNull [] message) {
        if (!LimboProtocol.CHANNEL.equals(channel)) {
            return;
        }

        final Optional<LimboProtocol.Message> decoded = LimboProtocol.decode(message);
        if (decoded.isEmpty()) {
            plugin.getLogger().warning("Dropped an unreadable " + LimboProtocol.CHANNEL
                    + " message for " + player.getName());
            return;
        }
        if (decoded.get().type() != LimboProtocol.Type.READY) {
            final WaitReason reason = decoded.get().reason();
            // Bukkit delivers plugin messages on the main thread already, so the title goes out
            // from here directly rather than through another scheduler hop.
            room.show(player, reason);
            return;
        }

        // READY runs limbo -> proxy only. Nothing sends one to us; if something does, it is a bug
        // in whatever sent it and not something to act on.
        plugin.getLogger().warning("Ignored a READY on " + LimboProtocol.CHANNEL
                + ", which only this server sends");
    }
}
