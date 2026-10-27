package eu.nordtal.season.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.value.GameContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class WheelTextTest {

    private static final MessageRenderer RENDERER = MessageRenderer.of(
                    Messages.load("messages/database", Locale.ENGLISH, Locale.GERMAN))
            .reading(new AppliedDocuments(WheelTextTest.class.getClassLoader(), Set.of("smp")));

    @Test
    void theWheelNamesItsPrizeInTheClientsOwnLanguage() {
        for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
            final Component rendered =
                    RENDERER.format(locale, Wheel.WON.with(new Wheel.Won(3, GameContent.of("block.minecraft.stone"))));
            final List<Component> parts = new ArrayList<>();
            flatten(rendered, parts);
            assertTrue(
                    parts.stream()
                            .anyMatch(part -> part instanceof final TranslatableComponent translatable
                                    && "block.minecraft.stone".equals(translatable.key())),
                    locale + ": the item has to arrive as a translatable component. It came out as " + rendered);
        }
    }

    @Test
    void theSpinsLeftNameTheirCountThroughTheTerm() {
        assertTrue(plain(Wheel.NONE.with(new Wheel.NoSpin(0))).endsWith("Come back tomorrow."));
        assertTrue(plain(Wheel.NONE.with(new Wheel.NoSpin(1))).endsWith("Still waiting: 1 extra spin."));
        assertTrue(plain(Wheel.NONE.with(new Wheel.NoSpin(4))).endsWith("Still waiting: 4 extra spins."));
        assertEquals("You have no spins right now.", plain(Wheel.AVAILABLE.with(new Wheel.Available(0))));
        assertEquals("You have 1 spin.", plain(Wheel.AVAILABLE.with(new Wheel.Available(1))));
        assertEquals("You have 5 spins.", plain(Wheel.AVAILABLE.with(new Wheel.Available(5))));
    }

    private static String plain(final Sent<?> sent) {
        return PlainTextComponentSerializer.plainText().serialize(RENDERER.format(Locale.ENGLISH, sent));
    }

    private static void flatten(final Component component, final List<Component> out) {
        out.add(component);
        component.children().forEach(child -> flatten(child, out));
    }
}
