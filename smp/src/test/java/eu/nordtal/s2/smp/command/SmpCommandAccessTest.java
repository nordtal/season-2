package eu.nordtal.s2.smp.command;

import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who may use {@code /smp}.
 *
 * <h2>The bug</h2>
 * The gate was {@code if (!(sender instanceof Player)) return true;} under a comment reading "the
 * console always may: it is the operator". The comment described the intent and the code described
 * something much wider - <em>everything</em> that is not a player, which on Paper includes a
 * command block's {@link BlockCommandSender}, the {@link ProxiedCommandSender} that
 * {@code /execute as … run …} builds, and a datapack function's sender. Any of those could have run
 * {@code /smp aura}, {@code /smp milestone unlock} and {@code /smp update restart} - the last of
 * which takes the whole network down after a one-minute countdown.
 *
 * <p>On this server that is a real surface rather than a theoretical one: the SMP is a place where
 * players build, so a command block is something the season hands them, and two third-party
 * datapacks are <em>required</em> for the server to start at all.</p>
 *
 * <h2>Why the senders are proxies</h2>
 * Every one of these is a Bukkit interface, and the decision only reads their type. A dynamic proxy
 * is an instance of the interface with no server behind it, which is exactly as much as this needs
 * - and it keeps the rule in {@code nordtal.paper-plugin} intact: nothing here reaches into Bukkit
 * for anything but a plain value.
 */
class SmpCommandAccessTest {

    private static final UUID SOMEBODY = UUID.fromString("00000000-0000-4000-8000-000000000001");

    /** Admin flag lookups must never even be consulted for a non-player. */
    private static final Predicate<UUID> NOBODY_IS_ADMIN = uuid -> false;
    private static final Predicate<UUID> EVERYBODY_IS_ADMIN = uuid -> true;

    @Test
    @DisplayName("the console may: it is the operator, and it is the way in when the flags are gone")
    void theConsoleMay() {
        assertTrue(SmpCommand.mayUse(sender(ConsoleCommandSender.class), NOBODY_IS_ADMIN));
    }

    @Test
    @DisplayName("a command block may not - this is the one the old check let through")
    void aCommandBlockMayNot() {
        assertFalse(SmpCommand.mayUse(sender(BlockCommandSender.class), NOBODY_IS_ADMIN),
                "a command block is not a Player, which is all the old check asked. On a server"
                        + " where players build and two datapacks are required, that is a way to"
                        + " reach /smp aura and /smp update restart.");
    }

    @Test
    @DisplayName("an /execute as … run … sender may not")
    void aProxiedSenderMayNot() {
        assertFalse(SmpCommand.mayUse(sender(ProxiedCommandSender.class), NOBODY_IS_ADMIN));
    }

    @Test
    @DisplayName("a remote console may not either - it is not the operator at the machine")
    void aRemoteConsoleMayNot() {
        // RCON is not enabled anywhere in this deployment, and if it ever were, "somebody who has
        // the RCON password" is not the same authority as "somebody with a shell in the container".
        assertFalse(SmpCommand.mayUse(sender(RemoteConsoleCommandSender.class), NOBODY_IS_ADMIN));
    }

    @Test
    @DisplayName("a player is admitted only on the database's admin flag")
    void aPlayerIsAskedAbout() {
        assertTrue(SmpCommand.mayUse(player(), EVERYBODY_IS_ADMIN));
        assertFalse(SmpCommand.mayUse(player(), NOBODY_IS_ADMIN));
    }

    // ---------------------------------------------------------------- senders with nothing behind them

    private static CommandSender sender(final Class<? extends CommandSender> type) {
        return (CommandSender) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(
                            "the decision must read the sender's type and nothing else, but it "
                                    + "called " + method.getName());
                });
    }

    private static CommandSender player() {
        return (CommandSender) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) {
                        return SOMEBODY;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
