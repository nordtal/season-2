package eu.nordtal.s2.networkcontrol.command;

import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.Player;

import eu.nordtal.s2.common.command.CommandAllowlist;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.networkcontrol.gate.LoginRoster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * season-2-ingame/13's proxy half: {@code CommandGate#onCommandExecute} paints {@code Tone.BAD}
 * already and calls the chime hook on every refusal - the same call site {@code CommandFilterTest}
 * holds Paper to. This file stays a hook-level test with a spy rather than a real
 * {@code NetworkControlSounds}: it is what proves the call site itself is right regardless of what
 * plays the sound, and {@code NetworkControlSoundsTest} covers the adapter that maps
 * {@link Feedback} to an actual {@code Player#playSound} call in production, wired by
 * {@code NetworkControlPlugin} since season-2-ingame/28. See the javadoc on {@link CommandGate.Chime}
 * for the measured answer to season-2-ingame/05's open question - the API says the proxy can reach a
 * player on any backend with {@code playSound}, the same way {@code RestartWatch} already reaches one
 * with {@code sendMessage} and {@code showTitle}.
 */
class CommandGateChimeTest {

    private static final UUID SOMEBODY = UUID.fromString("00000000-0000-4000-8000-000000000004");

    private final Messages messages = Messages.load(getClass().getClassLoader(),
            "messages/commands", Locales.DEFAULT);

    private static final class SpyChime implements CommandGate.Chime {

        private final List<Feedback> played = new ArrayList<>();

        @Override
        public void play(final Player player, final Feedback feedback) {
            played.add(feedback);
        }
    }

    @Test
    @DisplayName("a command refused for not being on the allowlist plays REFUSED through the hook")
    void refusalPlaysRefused() {
        final SpyChime chime = new SpyChime();
        final CommandGate gate = new CommandGate(new LoginRoster(), CommandAllowlist.NOTHING,
                messages, silentLogger(), chime);

        final CommandExecuteEvent event = new CommandExecuteEvent(player(), "spawn");
        gate.onCommandExecute(event);

        assertTrue(event.getResult() == CommandExecuteEvent.CommandResult.denied());
        assertEquals(List.of(Feedback.REFUSED), chime.played,
                "CommandGate paints Tone.BAD already but must also call the chime hook - the hook"
                        + " is silent in production until a real Sounds adapter is wired in, but the"
                        + " call site itself is what this test holds in place");
    }

    @Test
    @DisplayName("without a Chime the old constructor stays silent - nothing regresses for callers")
    void theOldConstructorIsSilentByDefault() {
        // No SpyChime reachable here at all: this is exactly the four-argument constructor every
        // existing caller (NetworkControlPlugin) still uses, proving it still compiles and runs
        // without ever having heard of Chime.
        final CommandGate gate = new CommandGate(new LoginRoster(), CommandAllowlist.NOTHING,
                messages, silentLogger());
        final CommandExecuteEvent event = new CommandExecuteEvent(player(), "spawn");
        gate.onCommandExecute(event);
        assertTrue(event.getResult() == CommandExecuteEvent.CommandResult.denied());
    }

    private static Logger silentLogger() {
        return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(),
                new Class<?>[]{Logger.class}, (proxy, method, args) -> {
                    if (method.getReturnType() == void.class) {
                        return null;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> {
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
