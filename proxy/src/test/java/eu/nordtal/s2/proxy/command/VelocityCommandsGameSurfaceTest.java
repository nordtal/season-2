package eu.nordtal.s2.proxy.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.network.NetworkCommands;
import eu.nordtal.s2.commands.network.NetworkEffects;
import eu.nordtal.s2.commands.smp.SmpCommands;
import eu.nordtal.s2.commands.smp.SmpEffects;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.access.AccessState;
import eu.nordtal.s2.database.access.MemberState;
import eu.nordtal.s2.messagerendering.ToneColours;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.proxy.gate.LoginRoster;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A command without {@link eu.nordtal.s2.commands.Surface#GAME} is absent from a player's command tree.
 *
 * The proxy's declarations are all console or web only, so an admin in game gets Minecraft's "Unknown command".
 */
class VelocityCommandsGameSurfaceTest {

    /** On the roster as an admin, so only the surface can refuse. */
    private static final UUID ADMIN = UUID.fromString("00000000-0000-4000-8000-00000000002a");

    private final Messages messages = Messages.load(getClass().getClassLoader(), "messages/commands", Locale.ENGLISH);

    @Test
    void aConsoleOnlyCommandIsGoneFromTheGame() {
        final VelocityCommands commands = adapter();
        for (final NordtalCommand<NetworkEffects> command : NetworkCommands.all()) {
            commands.local(command, refuse(NetworkEffects.class));
        }
        final var network = root(commands, "network");

        assertFalse(
                network.getRequirement().test(admin()),
                "/network is Surface.CONSOLE alone, so it must not be in the tree an admin standing"
                        + " in the lobby receives");
        assertTrue(network.getRequirement().test(console()), "the console keeps it - that surface is never taken away");
    }

    @Test
    void aWebCommandIsGoneFromTheGameToo() {
        final VelocityCommands commands = adapter();
        for (final NordtalCommand<SmpEffects> command : SmpCommands.all()) {
            commands.local(command, refuse(SmpEffects.class));
        }
        final var smp = root(commands, "smp");

        assertFalse(
                smp.getRequirement().test(admin()),
                "/smp milestone unlock is CONSOLE and WEB - the web is not a place a player types a"
                        + " command, so the tree loses it too");
        assertTrue(smp.getRequirement().test(console()), "the console keeps it");
    }

    private VelocityCommands adapter() {
        final LoginRoster roster = new LoginRoster();
        roster.remember(
                ADMIN,
                new AccessState(
                        ADMIN,
                        DiscordId.of("300000000000000042"),
                        MemberState.MEMBER,
                        true,
                        null,
                        false,
                        true,
                        false,
                        Locale.ENGLISH,
                        SeasonPhase.SMP,
                        null));
        return new VelocityCommands(refuse(ProxyServer.class), roster, messages, () -> ToneColours.DEFAULTS);
    }

    private static com.mojang.brigadier.tree.LiteralCommandNode<CommandSource> root(
            final VelocityCommands commands, final String literal) {
        final List<BrigadierCommand> built = commands.build();
        return built.stream()
                .map(BrigadierCommand::getNode)
                .filter(node -> node.getLiteral().equals(literal))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no /" + literal + " was built"));
    }

    /** A connected player who is an admin, and nothing else; anything further throws. */
    private static CommandSource admin() {
        return (CommandSource) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> ADMIN;
                    case "getUsername" -> "admin";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static CommandSource console() {
        return (CommandSource) Proxy.newProxyInstance(
                ConsoleCommandSource.class.getClassLoader(),
                new Class<?>[] {ConsoleCommandSource.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A stub whose whole contract is that nothing may call it. */
    @SuppressWarnings("unchecked")
    private static <T> T refuse(final Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            throw new UnsupportedOperationException(
                    "a refused command must never reach " + type.getSimpleName() + " (#" + method.getName() + ")");
        });
    }
}
