package eu.nordtal.s2.papercommon.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.database.inbox.MessagePreview;
import eu.nordtal.s2.database.inbox.ServerRefusal;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.spec.Display;
import eu.nordtal.s2.papercommon.command.Answer;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** A preview reaches the admin's player where its key is shown, and nobody else. */
class PreviewsTest {

    private static final PlayerId ALEX = PlayerId.of(UUID.fromString("00000000-0000-0000-0000-00000000000a"));

    private static final MessageRenderer RENDERER =
            MessageRenderer.of(Messages.load("messages/paper-common", Locale.ENGLISH, Locale.GERMAN));

    private static final MessageRef CONFIRM = new MessageRef("admin.confirm", Map.of("command", "/smp unlock"));

    /** Every call the player received, by method name and its one argument. */
    private final List<Object[]> calls = new ArrayList<>();

    private final Player alex = (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> {
                calls.add(new Object[] {method.getName(), args == null ? null : args[0]});
                return null;
            });

    private final Previews previews = new Previews(uuid -> uuid.equals(ALEX.value()) ? alex : null, RENDERER);

    private static MessagePreview preview(final Display shown) {
        return new MessagePreview(CONFIRM, "en", "Type <bad>{command}</bad>", shown);
    }

    private static String plain(final Object component) {
        return PlainTextComponentSerializer.plainText().serialize((Component) component);
    }

    @Test
    void aTitleIsShownAsATitleWithTheMessagesValues() {
        assertInstanceOf(Answer.Done.class, previews.show(ALEX, preview(Display.TITLE)));

        assertEquals(1, calls.size());
        assertEquals("showTitle", calls.getFirst()[0]);
        assertEquals("Type /smp unlock", plain(((Title) calls.getFirst()[1]).title()));
    }

    @Test
    void anActionBarIsShownThereAndAMenuLineInChat() {
        previews.show(ALEX, preview(Display.ACTION_BAR));
        previews.show(ALEX, preview(Display.GUI));

        assertEquals("sendActionBar", calls.get(0)[0]);
        assertEquals("sendMessage", calls.get(1)[0]);
        assertEquals("Type /smp unlock", plain(calls.get(1)[1]));
    }

    @Test
    void aPlayerWhoIsNotHereIsRefusedAndNothingIsShown() {
        final Answer answer = previews.show(
                PlayerId.of(UUID.fromString("00000000-0000-0000-0000-00000000000b")), preview(Display.CHAT));

        assertEquals(ServerRefusal.NOT_HERE, ((Answer.Refused) answer).refusal().reason());
        assertTrue(calls.isEmpty());
    }
}
