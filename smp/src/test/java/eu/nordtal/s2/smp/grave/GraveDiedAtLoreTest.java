package eu.nordtal.s2.smp.grave;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

/**
 * The grave head's lore prints "Died dd/mm/yyyy at x y z".
 *
 * This holds the rendered text in both languages against the real {@code messages/smp} bundle, the same way
 * {@link eu.nordtal.s2.smp.MessageBundlesTest} holds the wheel's prize line - there is no running Paper server in
 * this module's tests, so {@code Graves#head} itself (which needs a live {@code SkullMeta}) cannot be exercised
 * directly; the bundle key it renders through can be.
 */
class GraveDiedAtLoreTest {

    private static final Messages MESSAGES =
            Messages.load(GraveDiedAtLoreTest.class.getClassLoader(), "messages/smp", Locale.ENGLISH, Locale.GERMAN);
    private static final MessageRenderer RENDERER = MessageRenderer.of(MESSAGES);

    @Test
    void english() {
        assertEquals(
                "Died 17/09/2026 at 100 64 -200",
                plain(RENDERER.format(
                        Locale.ENGLISH, "smp.grave.died-at", "date", "17/09/2026", "x", 100, "y", 64, "z", -200)));
    }

    @Test
    void german() {
        assertEquals(
                "Gestorben am 17/09/2026 bei 100 64 -200",
                plain(RENDERER.format(
                        Locale.GERMAN, "smp.grave.died-at", "date", "17/09/2026", "x", 100, "y", 64, "z", -200)));
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
