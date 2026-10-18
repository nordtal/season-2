package eu.nordtal.season.smp.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Messages;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** The client draws a lore line as one row and never wraps it, so a long item list has to be many lines. */
class WantedItemsTest {

    private final MessageRenderer renderer = MessageRenderer.of(
            Messages.load(WantedItemsTest.class.getClassLoader(), "messages/smp", Locale.ENGLISH, Locale.GERMAN));

    @Test
    void everyWantedItemGetsALineOfItsOwn() {
        final List<Component> lines = WantedItems.lines(
                renderer,
                Locale.ENGLISH,
                List.of("nordtal:one", "nordtal:two", "nordtal:three", "nordtal:four", "nordtal:five"));

        assertEquals(5, lines.size());
        assertEquals("nordtal:one", PlainTextComponentSerializer.plainText().serialize(lines.getFirst()));
    }

    @Test
    void noItemsGiveNoLines() {
        assertEquals(0, WantedItems.lines(renderer, Locale.ENGLISH, List.of()).size());
    }
}
