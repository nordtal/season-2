package eu.nordtal.s2.papercommon.command;

import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.access.AccessCommands;
import eu.nordtal.s2.commands.access.AccessEffects;
import eu.nordtal.s2.commands.smp.SmpCommands;
import eu.nordtal.s2.commands.smp.SmpEffects;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.ToneColours;

import com.mojang.brigadier.tree.CommandNode;
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
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code steward/106}: a command that is not {@link eu.nordtal.s2.commands.Surface#GAME} is not in
 * a player's command tree at all - so Minecraft itself answers "Unknown command", and nothing in
 * this codebase answers anything.
 *
 * <h2>What this replaces, and why the replacement is the point</h2>
 * {@code season-2-ops/18} took every admin command off {@code GAME}, kept registering them anyway,
 * and answered a player who typed one with {@code command.not-in-game} - "that command still
 * exists, but not here any more". Till saw that sentence in game on 2026-09-17 and called it a
 * misreading of his own requirement: the commands are to be <em>gone</em>. The cost is that
 * Minecraft says "Unknown command", which is exactly what ops/18 was avoiding - and Till took that
 * cost deliberately, which is why this test asserts the cost rather than working around it.
 *
 * <h2>Registration and visibility are the same question here</h2>
 * The tree is one tree; Brigadier filters it per source through {@code requires}. There is no
 * "register for the console only" on Paper, so "not registered in game" <em>is</em> a
 * {@code requires} that no {@link Player} passes - and that is the shape the ticket's own fallback
 * sentence names. The console keeps every one of them, which
 * {@code AdminCommandsAreConsoleAndWebOnlyTest#consoleIsNeverTakenAway} says why.
 *
 * <h2>Seen red, 2026-09-17</h2>
 * Against {@code PaperCommands} exactly as ops/18 left it, both assertions below failed - the
 * requirement on {@code /access} and on {@code /smp farmreset} was {@code mayUse} alone, which an
 * admin player passes:
 *
 * <pre>
 * AdminCommandsAreGoneFromTheGameTest &gt; an admin player has no /access in their tree at all FAILED
 *     org.opentest4j.AssertionFailedError: /access carries no Surface.GAME on any of its five
 *     commands, so an admin standing in the world must not have the root in their tree ==&gt;
 *     expected: &lt;false&gt; but was: &lt;true&gt;
 * AdminCommandsAreGoneFromTheGameTest &gt; ...and no /smp farmreset either, while /smp status stays FAILED
 *     org.opentest4j.AssertionFailedError: /smp farmreset is CONSOLE and WEB, so it is not in a
 *     player's tree ==&gt; expected: &lt;false&gt; but was: &lt;true&gt;
 * </pre>
 */
class AdminCommandsAreGoneFromTheGameTest {

    /** An admin: the source that passed the old gate and is the whole point of the new one. */
    private static final UUID ADMIN = UUID.fromString("00000000-0000-4000-8000-00000000002a");

    private final Messages messages = Messages.load(getClass().getClassLoader(),
            "messages/commands", Locale.ENGLISH);

    @Test
    @DisplayName("an admin player has no /access in their tree at all")
    void accessIsGoneForPlayers() {
        final PaperCommands commands = adapter(Target.BOT);
        for (final NordtalCommand<AccessEffects> command : AccessCommands.all()) {
            commands.local(command, silent(AccessEffects.class));
        }
        final LiteralCommandNode<CommandSourceStack> access = root(commands.build(), "access");

        assertFalse(access.getRequirement().test(source(player())),
                "/access carries no Surface.GAME on any of its five commands, so an admin standing"
                        + " in the world must not have the root in their tree");
        assertTrue(access.getRequirement().test(source(sender(ConsoleCommandSender.class))),
                "the console keeps every one of them - that surface is never taken away");
    }

    @Test
    @DisplayName("...and no /smp farmreset either, while /smp status stays")
    void onlyTheOffGameBranchesGo() {
        final PaperCommands commands = adapter(Target.SMP);
        for (final NordtalCommand<SmpEffects> command : SmpCommands.all()) {
            commands.local(command, silent(SmpEffects.class));
        }
        final LiteralCommandNode<CommandSourceStack> smp = root(commands.build(), "smp");
        final Predicate<CommandSourceStack> farmreset = child(smp, "farmreset").getRequirement();
        final Predicate<CommandSourceStack> status = child(smp, "status").getRequirement();

        assertFalse(farmreset.test(source(player())),
                "/smp farmreset is CONSOLE and WEB, so it is not in a player's tree");
        assertTrue(farmreset.test(source(sender(ConsoleCommandSender.class))),
                "the console keeps it");
        assertTrue(status.test(source(player())),
                "/smp status is the one /smp command that kept Surface.GAME - cutting it would be"
                        + " this change reaching past what it was asked to do");
        assertTrue(smp.getRequirement().test(source(player())),
                "and the root stays open, because /smp status hangs off it");
    }

    private PaperCommands adapter(final Target here) {
        return new PaperCommands(silent(Plugin.class), messages, here, null,
                uuid -> Locale.ENGLISH, uuid -> true, uuid -> Optional.empty(),
                PaperUser.Chime.silent(), () -> ToneColours.DEFAULTS);
    }

    private static LiteralCommandNode<CommandSourceStack> root(
            final List<LiteralCommandNode<CommandSourceStack>> roots, final String literal) {
        return roots.stream()
                .filter(node -> node.getLiteral().equals(literal))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no /" + literal + " was built"));
    }

    private static CommandNode<CommandSourceStack> child(
            final CommandNode<CommandSourceStack> parent, final String literal) {
        final CommandNode<CommandSourceStack> found = parent.getChild(literal);
        if (found == null) {
            throw new AssertionError("no " + literal + " under /" + parent.getName());
        }
        return found;
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
                        return ADMIN;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
