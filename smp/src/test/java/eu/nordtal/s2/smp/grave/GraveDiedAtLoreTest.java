package eu.nordtal.s2.smp.grave;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.smp.SmpMessages;
import java.time.Instant;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

/** The grave head's lore prints the day of death and the place, checked through the real bundle in both languages. */
class GraveDiedAtLoreTest {

    private static final Messages MESSAGES =
            Messages.load(GraveDiedAtLoreTest.class.getClassLoader(), "messages/smp", Locale.ENGLISH, Locale.GERMAN);
    private static final MessageRenderer RENDERER = MessageRenderer.of(MESSAGES);
    private static final SmpMessages.Smp.Grave DIED = SmpMessages.MESSAGES.smp().grave();
    private static final Instant AT = Instant.parse("2026-09-17T10:00:00Z");

    @Test
    void english() {
        assertEquals(
                "Died Sep 17, 2026 at 100 64 -200",
                plain(RENDERER.format(Locale.ENGLISH, DIED.diedAt(AT, 100, 64, -200))));
    }

    @Test
    void german() {
        assertEquals(
                "Gestorben am 17.09.2026 bei 100 64 -200",
                plain(RENDERER.format(Locale.GERMAN, DIED.diedAt(AT, 100, 64, -200))));
    }

    /** The old hint key is replaced, not kept alongside the new line: it must be gone, not merely unused. */
    @Test
    void theOldHintKeyIsGone() {
        assertEquals(
                java.util.Set.of(),
                java.util.Set.of("smp.grave.owner-hint").stream()
                        .filter(key -> MESSAGES.hasTranslation(Locale.ENGLISH, key)
                                || MESSAGES.hasTranslation(Locale.GERMAN, key))
                        .collect(java.util.stream.Collectors.toSet()),
                "smp.grave.owner-hint should have been replaced by smp.grave.died-at, not left" + " behind unused");
    }

    private static String plain(final Component component) {
        final StringBuilder out = new StringBuilder();
        flatten(component, out);
        return out.toString();
    }

    private static void flatten(final Component component, final StringBuilder out) {
        if (component instanceof final TextComponent text) {
            out.append(text.content());
        }
        component.children().forEach(child -> flatten(child, out));
    }
}
