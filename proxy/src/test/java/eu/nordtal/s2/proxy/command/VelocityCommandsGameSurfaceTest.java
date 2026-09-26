package eu.nordtal.s2.proxy.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.phase.PhaseCommands;
import eu.nordtal.s2.commands.phase.PhaseEffects;
import eu.nordtal.s2.commands.update.UpdateCommands;
import eu.nordtal.s2.commands.update.UpdateEffects;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.MemberState;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.ToneColours;
import eu.nordtal.s2.proxy.gate.LoginRoster;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The proxy's half of the rule about {@link eu.nordtal.s2.commands.Surface#GAME} and a player's command tree.
 *
 * A command carrying no {@code GAME} surface is not in that tree at all. An admin typing the chat form of
 * {@code /update check} or {@code /phase show} is meant to get
 * Minecraft's own "Unknown command" rather than a bespoke not-in-game message: the commands are
 * meant to be gone from a player's view entirely, and a command that does not exist produces no
 * message to assert on, so this file asks the tree instead of a chat log.
 *
 * {@code ProxyPlugin} hands this adapter {@code PhaseCommands}, {@code NetworkCommands} and
 * {@code UpdateCommands} through {@code local()}, and nothing else: the five commands a player
 * types are native Brigadier beside this tree rather than declarations in it. All three carry no
 * {@code Surface.GAME} - {@code /network reload} and the {@code /update} family are
 * {@code CONSOLE} only, {@code /phase} is {@code CONSOLE} and {@code WEB}.
 * {@link VelocityUser#origin()} returns {@code GAME} for every connected player.
 */
class VelocityCommandsGameSurfaceTest {

    /** On the roster as an admin: the source that passed the old gate, and the point of the new. */
    private static final UUID ADMIN = UUID.fromString("00000000-0000-4000-8000-00000000002a");

    private final Messages messages = Messages.load(getClass().getClassLoader(), "messages/commands", Locale.ENGLISH);

    @Test
    void aConsoleOnlyCommandIsGoneFromTheGame() {
        final VelocityCommands commands = adapter();
        for (final NordtalCommand<UpdateEffects> command : UpdateCommands.all()) {
            commands.local(command, refuse(UpdateEffects.class));
        }
        final var update = root(commands, "update");

        assertFalse(
                update.getRequirement().test(admin()),
                "/update is Surface.CONSOLE alone, so it must not be in the tree an admin standing"
                        + " in the lobby receives");
        assertTrue(update.getRequirement().test(console()), "the console keeps it - that surface is never taken away");
    }

    @Test
    void aWebCommandIsGoneFromTheGameToo() {
        final VelocityCommands commands = adapter();
        for (final NordtalCommand<PhaseEffects> command : PhaseCommands.all()) {
            commands.local(command, refuse(PhaseEffects.class));
        }
        final var phase = root(commands, "phase");

        // The bare root too: Catalogue#rootDefault makes /phase run /phase show, a second way past an ungated root.
        assertFalse(
                phase.getRequirement().test(admin()),
                "/phase is CONSOLE and WEB - the web is not a place a player types a command, so"
                        + " the tree loses it too");
        assertTrue(phase.getRequirement().test(console()), "the console keeps it");
    }

    private VelocityCommands adapter() {
        final LoginRoster roster = new LoginRoster();
        roster.remember(
                ADMIN,
                new AccessState(
                        ADMIN,
                        "300000000000000042",
                        MemberState.MEMBER,
                        true,
                        null,
                        false,
                        true,
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

    /** A connected player who is an admin, and nothing else - anything further throws. */
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
