package eu.nordtal.s2.papercommon.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Who may use an admin command on a Paper server.
 *
 * The gate reads only the sender's type: a {@link ConsoleCommandSender} always may, everything
 * else is asked for the admin flag. A command block's {@link BlockCommandSender} and the
 * {@link ProxiedCommandSender} that {@code /execute as … run …} builds are real surfaces here -
 * players build with command blocks, and two third-party datapacks are required for the server to
 * start at all.
 *
 * Every sender is a dynamic proxy: an instance of the Bukkit interface with no server behind it,
 * which is exactly as much as a type-only decision needs.
 */
class PaperCommandsAccessTest {

    private static final UUID SOMEBODY = UUID.fromString("00000000-0000-4000-8000-000000000001");

    /** Admin flag lookups must never even be consulted for a non-player. */
    private static final Predicate<UUID> NOBODY_IS_ADMIN = PaperCommandsAccessTest::nobodyIsAdmin;

    private static final Predicate<UUID> EVERYBODY_IS_ADMIN = PaperCommandsAccessTest::everybodyIsAdmin;

    private static boolean nobodyIsAdmin(final UUID uuid) {
        return false;
    }

    private static boolean everybodyIsAdmin(final UUID uuid) {
        return true;
    }

    @Test
    void theConsoleMay() {
        assertTrue(PaperCommands.mayUse(sender(ConsoleCommandSender.class), NOBODY_IS_ADMIN));
    }

    @Test
    void aCommandBlockMayNot() {
        assertFalse(
                PaperCommands.mayUse(sender(BlockCommandSender.class), NOBODY_IS_ADMIN),
                "a command block is not a Player - on a server where players build and two"
                        + " datapacks are required, that is a real way to reach /smp aura and"
                        + " /smp update restart.");
    }

    @Test
    void aProxiedSenderMayNot() {
        assertFalse(PaperCommands.mayUse(sender(ProxiedCommandSender.class), NOBODY_IS_ADMIN));
    }

    @Test
    void aRemoteConsoleMayNot() {
        // RCON is not enabled here; having its password is not the same authority as a shell in the container.
        assertFalse(PaperCommands.mayUse(sender(RemoteConsoleCommandSender.class), NOBODY_IS_ADMIN));
    }

    @Test
    void aPlayerIsAskedAbout() {
        assertTrue(PaperCommands.mayUse(player(), EVERYBODY_IS_ADMIN));
        assertFalse(PaperCommands.mayUse(player(), NOBODY_IS_ADMIN));
    }

    private static CommandSender sender(final Class<? extends CommandSender> type) {
        return (CommandSender)
                Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(
                            "the decision must read the sender's type and nothing else, but it " + "called "
                                    + method.getName());
                });
    }

    private static CommandSender player() {
        return (CommandSender) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) {
                        return SOMEBODY;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
