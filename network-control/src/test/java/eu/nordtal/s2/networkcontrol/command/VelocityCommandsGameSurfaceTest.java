package eu.nordtal.s2.networkcontrol.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
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
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.networkcontrol.gate.LoginRoster;

import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code season-2-ops/25}: the proxy's own half of the gap {@code AdminCommandsAreConsoleAndWebOnly}
 * opened and {@code PaperCommands} closed on 2026-09-15.
 *
 * <h2>Why the gap is reachable here at all, which was the first thing to settle</h2>
 * The ticket allowed for the answer being a proof rather than a patch - {@code PaperCommands}'
 * javadoc says {@link eu.nordtal.s2.commands.Target#PROXY} commands are "deliberately not
 * registered" on a backend, so one could hope the reverse held and no {@code Origin.GAME} case
 * existed on this side. It does not hold. {@code NetworkControlPlugin} hands this adapter
 * {@code PhaseCommands}, {@code NetworkCommands}, {@code UpdateCommands}, {@code ChatCommands} and
 * {@code InfoCommands} through {@code local()}, and after {@code season-2-ops/18} the first three
 * of those carry no {@link eu.nordtal.s2.commands.Surface#GAME} any more - {@code /network reload}
 * and the {@code /update} family are {@code CONSOLE} only, {@code /phase} is {@code CONSOLE} and
 * {@code WEB}. {@link VelocityUser#origin()} returns {@code GAME} for every connected player. So an
 * admin typing {@code /phase set} in chat is not a hypothetical path: it is the normal one, and
 * before this test it ran the command.
 *
 * <h2>Seen red, 2026-09-16</h2>
 * With the {@code Origin.GAME} block removed from {@code VelocityCommands#run} - that is, with the
 * file exactly as {@code season-2-ops/18} left it - both cases below fail on the effects proxy:
 * {@code run()} falls through to {@code entry.run()}, the command calls its effects, and the stub
 * throws {@code UnsupportedOperationException: a refused command must never reach UpdateEffects}.
 * Which is the finding stated as loudly as it can be: the command was executed.
 */
class VelocityCommandsGameSurfaceTest {

    /** On the roster as an admin: the only source that gets past the roots' {@code requires}. */
    private static final UUID ADMIN = UUID.fromString("00000000-0000-4000-8000-00000000002a");

    private final Messages messages = Messages.load(getClass().getClassLoader(),
            "messages/commands", Locale.ENGLISH);

    private final List<String> heard = new ArrayList<>();

    @Test
    @DisplayName("an admin typing the old chat form of a console-only command is told where it went")
    void aConsoleOnlyCommandNamesTheConsole() throws CommandSyntaxException {
        final VelocityCommands commands = adapter();
        for (final NordtalCommand<UpdateEffects> command : UpdateCommands.all()) {
            commands.local(command, refuse(UpdateEffects.class));
        }

        // Never CommandSyntaxException: that is what a client renders as command.unknown, and
        // season-2-ingame/13 forbids it. An exception here fails the test by itself.
        dispatcher(commands).execute("update check", admin());

        assertEquals(List.of(rendered("command.not-in-game")), heard,
                "/update check is Surface.CONSOLE alone, so the console is the only place left");
    }

    @Test
    @DisplayName("...and one that kept Surface.WEB points at Steward instead")
    void aWebCommandNamesTheWebInterface() throws CommandSyntaxException {
        final VelocityCommands commands = adapter();
        for (final NordtalCommand<PhaseEffects> command : PhaseCommands.all()) {
            commands.local(command, refuse(PhaseEffects.class));
        }

        // The bare root as well as the full form: Catalogue#rootDefault makes /phase run /phase
        // show, which is a second way into run() and would have been a second way past the check.
        dispatcher(commands).execute("phase show", admin());
        dispatcher(commands).execute("phase", admin());

        assertEquals(List.of(rendered("command.not-in-game.web"),
                        rendered("command.not-in-game.web")), heard,
                "/phase carries Surface.WEB, so telling this admin to use the console would be a"
                        + " lie - PhaseCommands moved to CONSOLE and WEB on 2026-09-16");
    }

    private VelocityCommands adapter() {
        final LoginRoster roster = new LoginRoster();
        roster.remember(ADMIN, new AccessState(ADMIN, "300000000000000042", MemberState.MEMBER,
                true, null, false, true, Locale.ENGLISH, SeasonPhase.SMP, null));
        return new VelocityCommands(refuse(ProxyServer.class), roster, messages);
    }

    private static CommandDispatcher<CommandSource> dispatcher(final VelocityCommands commands) {
        final CommandDispatcher<CommandSource> dispatcher = new CommandDispatcher<>();
        for (final BrigadierCommand root : commands.build()) {
            dispatcher.getRoot().addChild(root.getNode());
        }
        return dispatcher;
    }

    /** What the player would actually read, in the language the roster logged them in with. */
    private String rendered(final String key) {
        return PlainTextComponentSerializer.plainText()
                .serialize(MessageRenderer.of(messages).format(Locale.ENGLISH, key));
    }

    /**
     * A connected player who is an admin. Everything but the three calls a refusal makes throws, so
     * a path that needs more than this has changed shape and should say so rather than pass.
     */
    private CommandSource admin() {
        return (CommandSource) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> ADMIN;
                    case "getUsername" -> "admin";
                    case "sendMessage" -> {
                        heard.add(PlainTextComponentSerializer.plainText()
                                .serialize(((ComponentLike) args[0]).asComponent()));
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
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
