package eu.nordtal.s2.papercommon.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.command.CommandAllowlist;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.common.message.ToneColours;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.command.UnknownCommandEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/**
 * season-2-ingame/13: a refused command paints {@link eu.nordtal.s2.common.message.Tone#BAD}
 * already - both refusal sites build their own {@code Component} and never go through
 * {@link eu.nordtal.s2.commands.NordtalUser#reply}, which is where the {@link Feedback} overload
 * lives - so the colour arrived and the sound never did. Till, 2026-09-15: the sound has to play
 * "auch generell bei fremden Befehlen".
 *
 * <h2>Seen red before the fix</h2>
 * Run against {@code CommandFilter} before this ticket, this class does not compile: there was no
 * constructor taking a {@link PaperUser.Chime} at all, {@link #onUnknownCommand} never called one,
 * and neither did {@link #onCommand}. The ticket's own inventory table names exactly this gap -
 * {@code CommandFilter#onUnknownCommand}, {@code Tone.BAD} present, no {@code Feedback} - and this
 * is the test that holds it to "plays REFUSED", which is what "must fail against today's code"
 * means for a class that could not previously express the assertion at all.
 */
class CommandFilterTest {

    private static final UUID SOMEBODY = UUID.fromString("00000000-0000-4000-8000-000000000003");

    private final Messages messages = Messages.load(getClass().getClassLoader(), "messages/commands", Locales.DEFAULT);
    private final PlayerLocales locales = new PlayerLocales(uuid -> Locales.DEFAULT);

    /** Records every play() call rather than making a sound - there is nothing to hear in a test. */
    private static final class SpyChime implements PaperUser.Chime {

        private final List<Feedback> played = new ArrayList<>();

        @Override
        public void play(final Player player, final Feedback feedback) {
            played.add(feedback);
        }
    }

    private CommandFilter filter(final CommandFilter.Source source, final SpyChime chime) {
        return new CommandFilter(
                silentPlugin(),
                source,
                uuid -> false,
                locales,
                messages,
                silentLogger(),
                () -> ToneColours.DEFAULTS,
                chime);
    }

    @Test
    @DisplayName("a command Paper cannot find at all plays REFUSED, not just Tone.BAD")
    void unknownCommandPlaysRefused() {
        final SpyChime chime = new SpyChime();
        final CommandFilter filter = filter(() -> Optional.empty(), chime);

        final UnknownCommandEvent event = new UnknownCommandEvent(
                commandSourceStack(player()),
                "made-up-command",
                net.kyori.adventure.text.Component.text("placeholder"));

        filter.onUnknownCommand(event);

        assertEquals(
                List.of(Feedback.REFUSED),
                chime.played,
                "onUnknownCommand paints Tone.BAD already (see refusal()) but must also play"
                        + " Feedback.REFUSED - season-2-ingame/13");
    }

    @Test
    @DisplayName("the console typing an unknown command triggers no chime - it has no ears")
    void consoleUnknownCommandIsSilent() {
        final SpyChime chime = new SpyChime();
        final CommandFilter filter = filter(() -> Optional.empty(), chime);

        final UnknownCommandEvent event = new UnknownCommandEvent(
                commandSourceStack(sender(ConsoleCommandSender.class)),
                "made-up-command",
                net.kyori.adventure.text.Component.text("placeholder"));

        filter.onUnknownCommand(event);

        assertTrue(chime.played.isEmpty(), "the console has no ears and must not be asked to play" + " anything");
    }

    @Test
    @DisplayName("a command that exists but is not on the allowlist plays REFUSED too")
    void disallowedCommandPlaysRefused() {
        final SpyChime chime = new SpyChime();
        final CommandFilter filter = filter(() -> Optional.of(CommandAllowlist.NOTHING), chime);
        filter.refresh();

        // The 3-arg constructor, not the 2-arg one: the 2-arg form calls player.getServer() to
        // default the recipient set to every online player, which this test's Player proxy has no
        // server behind to answer. An explicit empty recipient set is unrelated to what is under
        // test here - onCommand never reads it.
        final PlayerCommandPreprocessEvent event =
                new PlayerCommandPreprocessEvent(player(), "/spawn", java.util.Set.of());

        filter.onCommand(event);

        assertTrue(event.isCancelled());
        assertEquals(List.of(Feedback.REFUSED), chime.played);
    }

    // ---------------------------------------------------------------- stand-ins, no server behind them

    private static Plugin silentPlugin() {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(
                            "the constructor must not call " + "Plugin#" + method.getName());
                });
    }

    private static Logger silentLogger() {
        return (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(), new Class<?>[] {Logger.class}, (proxy, method, args) -> {
                    // refresh() logs on a genuine change; everything else here must stay quiet.
                    if (method.getReturnType() == void.class) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static io.papermc.paper.command.brigadier.CommandSourceStack commandSourceStack(
            final CommandSender sender) {
        return (io.papermc.paper.command.brigadier.CommandSourceStack) Proxy.newProxyInstance(
                io.papermc.paper.command.brigadier.CommandSourceStack.class.getClassLoader(),
                new Class<?>[] {io.papermc.paper.command.brigadier.CommandSourceStack.class},
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

    private static Player player() {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) {
                        return SOMEBODY;
                    }
                    if ("sendMessage".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
