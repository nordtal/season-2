package eu.nordtal.s2.networkcontrol.command;

import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.smp.SmpCommands;
import eu.nordtal.s2.commands.smp.SmpEffects;
import eu.nordtal.s2.commands.update.UpdateCommands;
import eu.nordtal.s2.commands.update.UpdateEffects;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.networkcontrol.gate.LoginRoster;

import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same hole as {@code PaperCommandsRootGateTest}, on the adapter that matters more: Velocity
 * runs every command it knows for every player, so an ungated root here ran the report for anybody
 * in the network. A gated root has a second effect on the proxy - Velocity forwards a command the
 * source may not use to the backend, which is where a non-admin's {@code /update} now goes to be
 * refused.
 */
class VelocityCommandsRootGateTest {

    private static final UUID SOMEBODY = UUID.fromString("00000000-0000-4000-8000-000000000003");

    private final Messages messages = Messages.load(getClass().getClassLoader(),
            "messages/commands", Locale.ENGLISH);

    @Test
    @DisplayName("/update, entirely admin-only, is closed to a player who is not on the roster as one")
    void anAdminOnlyRootIsGated() {
        final VelocityCommands commands = adapter();
        for (final NordtalCommand<UpdateEffects> command : UpdateCommands.all()) {
            commands.local(command, silent(UpdateEffects.class));
        }
        final BrigadierCommand update = root(commands.build(), "update");

        assertFalse(update.getNode().getRequirement().test(player()));
        assertTrue(update.getNode().getRequirement().test(silent(CommandSource.class)),
                "the console is not a Player, and on the proxy that is the operator");
    }

    @Test
    @DisplayName("a root with an open command under it stays open - the tree shape, tried on /smp")
    void aRootWithSomethingOpenStaysOpen() {
        // /smp is not the proxy.s to serve; it is the one declared root with a command any player
        // may run (/smp status), which is what this case needs. /phase is entirely admin-only
        // since finding 102 and is therefore gated as a whole - correctly.
        final VelocityCommands commands = adapter();
        for (final NordtalCommand<SmpEffects> command : SmpCommands.all()) {
            commands.local(command, silent(SmpEffects.class));
        }
        assertTrue(root(commands.build(), "smp").getNode().getRequirement().test(player()));
    }

    private VelocityCommands adapter() {
        return new VelocityCommands(silent(ProxyServer.class), new LoginRoster(), messages);
    }

    private static BrigadierCommand root(final List<BrigadierCommand> roots, final String literal) {
        return roots.stream()
                .filter(command -> command.getNode().getLiteral().equals(literal))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no /" + literal + " was built"));
    }

    @SuppressWarnings("unchecked")
    private static <T> T silent(final Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("building a tree must not call "
                            + type.getSimpleName() + "#" + method.getName());
                });
    }

    private static CommandSource player() {
        return (CommandSource) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) {
                        return SOMEBODY;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
