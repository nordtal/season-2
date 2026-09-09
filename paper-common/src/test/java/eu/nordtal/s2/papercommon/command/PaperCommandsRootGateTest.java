package eu.nordtal.s2.papercommon.command;

import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.smp.SmpCommands;
import eu.nordtal.s2.commands.smp.SmpEffects;
import eu.nordtal.s2.commands.update.UpdateCommands;
import eu.nordtal.s2.commands.update.UpdateEffects;
import eu.nordtal.s2.common.message.Messages;

import com.mojang.brigadier.tree.LiteralCommandNode;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Brigadier's {@code requires} sits on nodes, and until 2026-09-08 only the first-level children
 * carried it. {@code /update} is a root whose bare form is itself a command, so it had no gated
 * node above it at all and any player could run the report. The tree is built here without a
 * server - {@code Commands.literal} is plain Brigadier - and the root's own requirement is asked.
 */
class PaperCommandsRootGateTest {

    private static final UUID SOMEBODY = UUID.fromString("00000000-0000-4000-8000-000000000002");

    private final Messages messages = Messages.load(getClass().getClassLoader(),
            "messages/commands", Locale.ENGLISH);

    @Test
    @DisplayName("/update, a root that is entirely admin-only, is closed to a player and open to the console")
    void anAdminOnlyRootIsGated() {
        final PaperCommands commands = adapter();
        for (final NordtalCommand<UpdateEffects> command : UpdateCommands.all()) {
            commands.local(command, silent(UpdateEffects.class));
        }
        final LiteralCommandNode<CommandSourceStack> update = root(commands.build(), "update");

        assertFalse(update.getRequirement().test(source(player())),
                "a player who is not an admin must not see or run the bare /update");
        assertTrue(update.getRequirement().test(source(sender(ConsoleCommandSender.class))));
    }

    @Test
    @DisplayName("/smp, a root with an open command under it, stays open - /smp status is anybody's")
    void aRootWithSomethingOpenStaysOpen() {
        final PaperCommands commands = adapter();
        for (final NordtalCommand<SmpEffects> command : SmpCommands.all()) {
            commands.local(command, silent(SmpEffects.class));
        }
        final LiteralCommandNode<CommandSourceStack> smp = root(commands.build(), "smp");

        assertTrue(smp.getRequirement().test(source(player())),
                "gating this root would hide /smp status from the players it was declared for");
    }

    private PaperCommands adapter() {
        return new PaperCommands(silent(Plugin.class), messages, Target.SMP, null,
                uuid -> Locale.ENGLISH, uuid -> false, uuid -> Optional.empty(),
                PaperUser.Chime.silent());
    }

    private static LiteralCommandNode<CommandSourceStack> root(
            final List<LiteralCommandNode<CommandSourceStack>> roots, final String literal) {
        return roots.stream()
                .filter(node -> node.getLiteral().equals(literal))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no /" + literal + " was built"));
    }

    // ---------------------------------------------------------------- stand-ins

    @SuppressWarnings("unchecked")
    private static <T> T silent(final Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("building a tree must not call "
                            + type.getSimpleName() + "#" + method.getName());
                });
    }

    private static CommandSourceStack source(final CommandSender sender) {
        return (CommandSourceStack) Proxy.newProxyInstance(
                CommandSourceStack.class.getClassLoader(), new Class<?>[]{CommandSourceStack.class},
                (proxy, method, args) -> {
                    if ("getSender".equals(method.getName())) {
                        return sender;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static CommandSender sender(final Class<? extends CommandSender> type) {
        return (CommandSender) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static CommandSender player() {
        return (CommandSender) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) {
                        return SOMEBODY;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
