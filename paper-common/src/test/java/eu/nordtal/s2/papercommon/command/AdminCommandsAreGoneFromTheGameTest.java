package eu.nordtal.s2.papercommon.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.access.AccessCommands;
import eu.nordtal.s2.commands.access.AccessEffects;
import eu.nordtal.s2.commands.smp.SmpCommands;
import eu.nordtal.s2.commands.smp.SmpEffects;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.ToneColours;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * A command without {@link eu.nordtal.s2.commands.Surface#GAME} is absent from a player's command tree.
 *
 * Minecraft answers "Unknown command" and nothing here answers anything else.
 *
 * The tree is one tree, filtered per source through Brigadier's {@code requires}: there is no
 * "register for the console only" on Paper, so being off {@code GAME} means no {@link Player}
 * passes that requirement. The console keeps every one of these commands.
 */
class AdminCommandsAreGoneFromTheGameTest {

    /** An admin: the source that passed the old gate and is the whole point of the new one. */
    private static final UUID ADMIN = UUID.fromString("00000000-0000-4000-8000-00000000002a");

    private final Messages messages = Messages.load(getClass().getClassLoader(), "messages/commands", Locale.ENGLISH);

    @Test
    void accessIsGoneForPlayers() {
        final PaperCommands commands = adapter(Target.BOT);
        for (final NordtalCommand<AccessEffects> command : AccessCommands.all()) {
            commands.local(command, silent(AccessEffects.class));
        }
        final LiteralCommandNode<CommandSourceStack> access = root(commands.build(), "access");

        assertFalse(
                access.getRequirement().test(source(player())),
                "/access carries no Surface.GAME on any of its five commands, so an admin standing"
                        + " in the world must not have the root in their tree");
        assertTrue(
                access.getRequirement().test(source(sender(ConsoleCommandSender.class))),
                "the console keeps every one of them - that surface is never taken away");
    }

    @Test
    void smpReloadIsGoneWhileSmpStatusStays() {
        final PaperCommands commands = adapter(Target.SMP);
        for (final NordtalCommand<SmpEffects> command : SmpCommands.all()) {
            commands.local(command, silent(SmpEffects.class));
        }
        // /smp status is native Brigadier, hung on the root by the smp plugin itself.
        commands.extraOpen("smp", Commands.literal("status"));
        final LiteralCommandNode<CommandSourceStack> smp = root(commands.build(), "smp");
        final Predicate<CommandSourceStack> reload = child(smp, "reload").getRequirement();
        final Predicate<CommandSourceStack> status = child(smp, "status").getRequirement();

        assertFalse(reload.test(source(player())), "/smp reload is CONSOLE only, so it is not in a player's tree");
        assertTrue(reload.test(source(sender(ConsoleCommandSender.class))), "the console keeps it");
        assertTrue(
                status.test(source(player())),
                "/smp status is the one /smp command a player may type - cutting it would be"
                        + " this change reaching past what it was asked to do");
        assertTrue(
                smp.getRequirement().test(source(player())),
                "and the root stays open, because /smp status hangs off it");
    }

    private PaperCommands adapter(final Target here) {
        return new PaperCommands(
                silent(Plugin.class),
                messages,
                here,
                null,
                uuid -> Locale.ENGLISH,
                uuid -> true,
                uuid -> Optional.empty(),
                PaperUser.Chime.silent(),
                () -> ToneColours.DEFAULTS);
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

    private static <T> T silent(final Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            throw new UnsupportedOperationException(
                    "building a tree must not call " + type.getSimpleName() + "#" + method.getName());
        });
    }

    private static CommandSourceStack source(final CommandSender sender) {
        return (CommandSourceStack) Proxy.newProxyInstance(
                CommandSourceStack.class.getClassLoader(),
                new Class<?>[] {CommandSourceStack.class},
                (proxy, method, args) -> {
                    if ("getSender".equals(method.getName())) {
                        return sender;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static CommandSender sender(final Class<? extends CommandSender> type) {
        return (CommandSender)
                Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static CommandSender player() {
        return (CommandSender) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) {
                        return ADMIN;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
