package eu.nordtal.season.papercommon.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messagerendering.GameLines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.papercommon.player.Identities;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class SystemLinesTest {

    private final SystemLines lines = new SystemLines(
            MessageRenderer.of(Messages.load("messages/database", Locale.ENGLISH, Locale.GERMAN)),
            new Identities(ignored -> List.of()));

    @Test
    void chatLosesPrivateUseCodePoints() {
        final AsyncChatEvent event = chat("before " + Character.toString(0xFE000) + " after");

        lines.onChat(event);

        assertFalse(event.isCancelled());
        assertEquals("before  after", GameLines.text(event.message()));
    }

    @Test
    void chatThatIsEmptyAfterFilteringIsCancelled() {
        final AsyncChatEvent event = chat(Character.toString(0xFE000));

        lines.onChat(event);

        assertTrue(event.isCancelled());
    }

    private static AsyncChatEvent chat(final String text) {
        return new AsyncChatEvent(
                true,
                player(),
                Set.of(),
                (source, displayName, message, viewer) -> message,
                Component.text(text),
                Component.text(text),
                unused(SignedMessage.class));
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(
                SystemLinesTest.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> UUID.fromString("00000000-0000-0000-0000-00000000000a");
                    case "getName" -> "Alex";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static <T> T unused(final Class<T> type) {
        return type.cast(Proxy.newProxyInstance(
                SystemLinesTest.class.getClassLoader(), new Class<?>[] {type}, (proxy, method, arguments) -> {
                    throw new UnsupportedOperationException(method.getName());
                }));
    }
}
