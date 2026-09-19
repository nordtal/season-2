package eu.nordtal.s2.proxy.command;

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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proxy's half of steward/106: a command that carries no
 * {@link eu.nordtal.s2.commands.Surface#GAME} is not in a player's command tree at all.
 *
 * <h2>What this test used to hold, and why it now holds the opposite</h2>
 * Written for {@code season-2-ops/25} on 2026-09-16, it asserted that an admin typing the old chat
 * form of {@code /update check} or {@code /phase show} heard {@code command.not-in-game} - "that
 * command still exists, but not here any more". Till read that sentence in game on 2026-09-17 and
 * called it a misreading of his own requirement: the commands are to be <b>gone</b>. The cost is
 * that Minecraft answers "Unknown command", which is the very thing {@code ops/18} built that key
 * to avoid, and Till took the cost deliberately. So the two keys are deleted and this file asks the
 * tree instead of the chat log - which is also the only place the new answer can be seen, because
 * a command that does not exist produces no message to assert on.
 *
 * <h2>Why the gap is reachable here at all, unchanged from ops/25</h2>
 * {@code ProxyPlugin} hands this adapter {@code PhaseCommands}, {@code NetworkCommands},
 * {@code UpdateCommands}, {@code ChatCommands} and {@code InfoCommands} through {@code local()},
 * and after {@code season-2-ops/18} the first three carry no {@code Surface.GAME} - {@code /network
 * reload} and the {@code /update} family are {@code CONSOLE} only, {@code /phase} is {@code CONSOLE}
 * and {@code WEB}. {@link VelocityUser#origin()} returns {@code GAME} for every connected player.
 *
 * <h2>Seen red, 2026-09-17</h2>
 * Against {@code VelocityCommands} exactly as ops/25 left it - {@code requires} being
 * {@code mayUse} alone, which an admin passes - both assertions failed:
 *
 * <pre>
 * VelocityCommandsGameSurfaceTest &gt; a console-only command is not in an admin's tree FAILED
 *     org.opentest4j.AssertionFailedError: /update is Surface.CONSOLE alone, so it must not be in
 *     the tree an admin standing in the lobby receives ==&gt; expected: &lt;false&gt; but was: &lt;true&gt;
 * VelocityCommandsGameSurfaceTest > ...and neither is one that kept Surface.WEB FAILED
 *     org.opentest4j.AssertionFailedError: /phase is CONSOLE and WEB - the web is not a place a
 *     player types a command, so the tree loses it too ==&gt; expected: &lt;false&gt; but was: &lt;true&gt;
 * </pre>
 */
class VelocityCommandsGameSurfaceTest {

    /** On the roster as an admin: the source that passed the old gate, and the point of the new. */
    private static final UUID ADMIN = UUID.fromString("00000000-0000-4000-8000-00000000002a");

    private final Messages messages = Messages.load(getClass().getClassLoader(),
            "messages/commands", Locale.ENGLISH);

    @Test
    @DisplayName("a console-only command is not in an admin's tree")
    void aConsoleOnlyCommandIsGoneFromTheGame() {
        final VelocityCommands commands = adapter();
        for (final NordtalCommand<UpdateEffects> command : UpdateCommands.all()) {
            commands.local(command, refuse(UpdateEffects.class));
        }
        final var update = root(commands, "update");

        assertFalse(update.getRequirement().test(admin()),
                "/update is Surface.CONSOLE alone, so it must not be in the tree an admin standing"
                        + " in the lobby receives");
        assertTrue(update.getRequirement().test(console()),
                "the console keeps it - that surface is never taken away");
    }

    @Test
    @DisplayName("...and neither is one that kept Surface.WEB")
    void aWebCommandIsGoneFromTheGameToo() {
        final VelocityCommands commands = adapter();
        for (final NordtalCommand<PhaseEffects> command : PhaseCommands.all()) {
            commands.local(command, refuse(PhaseEffects.class));
        }
        final var phase = root(commands, "phase");

        // The bare root as well: Catalogue#rootDefault makes /phase run /phase show, which was a
        // second way into run() and would be a second way past the gate if the root were ungated.
        assertFalse(phase.getRequirement().test(admin()),
                "/phase is CONSOLE and WEB - the web is not a place a player types a command, so"
                        + " the tree loses it too");
        assertTrue(phase.getRequirement().test(console()), "the console keeps it");
    }

    private VelocityCommands adapter() {
        final LoginRoster roster = new LoginRoster();
        roster.remember(ADMIN, new AccessState(ADMIN, "300000000000000042", MemberState.MEMBER,
                true, null, false, true, Locale.ENGLISH, SeasonPhase.SMP, null));
        return new VelocityCommands(refuse(ProxyServer.class), roster, messages,
                () -> ToneColours.DEFAULTS);
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
        return (CommandSource) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> ADMIN;
                    case "getUsername" -> "admin";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static CommandSource console() {
        return (CommandSource) Proxy.newProxyInstance(ConsoleCommandSource.class.getClassLoader(),
                new Class<?>[]{ConsoleCommandSource.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A stub whose whole contract is that nothing may call it. */
    @SuppressWarnings("unchecked")
    private static <T> T refuse(final Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("a refused command must never reach "
                            + type.getSimpleName() + " (#" + method.getName() + ")");
                });
    }
}
