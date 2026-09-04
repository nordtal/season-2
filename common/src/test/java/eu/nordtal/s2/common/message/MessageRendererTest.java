package eu.nordtal.s2.common.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules {@link MessageRenderer} exists to keep. The one worth reading twice is
 * {@link #aValueCannotInjectTags()}: a message is a template the repository controls, but a
 * placeholder value is a player name.
 */
class MessageRendererTest {

    private static final MessageRenderer RENDER =
            new MessageRenderer(Messages.load("messages/render", Locale.ENGLISH));

    /**
     * Flattens a component to its text. Adventure 5 moved {@code PlainTextComponentSerializer} into
     * its own artifact, and a test-only dependency on it would buy nothing this cannot do in five
     * lines.
     */
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

    @Test
    @DisplayName("a message with no tags renders as its own text")
    void untaggedTextSurvives() {
        assertEquals("Nothing to parse here", plain(RENDER.get(Locale.ENGLISH, "plain")));
    }

    @Test
    @DisplayName("tags in a message are parsed, not shown")
    void tagsAreParsed() {
        final Component rendered = RENDER.get(Locale.ENGLISH, "tagged");
        assertEquals("danger and calm", plain(rendered));
        assertTrue(rendered.children().stream()
                        .anyMatch(child -> NamedTextColor.RED.equals(child.color())),
                "the <red> tag did not become a colour");
    }

    @Test
    @DisplayName("placeholders are substituted before parsing")
    void placeholdersSubstitute() {
        assertEquals("Hello Till, you have 3 left",
                plain(RENDER.format(Locale.ENGLISH, "greeting", "name", "Till", "count", 3)));
    }

    @Test
    @DisplayName("a value containing a tag cannot inject MiniMessage")
    void aValueCannotInjectTags() {
        final Component rendered =
                RENDER.format(Locale.ENGLISH, "greeting", "name", "<red>evil</red>", "count", 0);
        assertEquals("Hello <red>evil</red>, you have 0 left", plain(rendered),
                "a player whose name contains MiniMessage syntax coloured the message");
    }

    @Test
    @DisplayName("an odd parameter count is refused rather than silently dropping one")
    void oddParametersAreRefused() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> RENDER.format(Locale.ENGLISH, "greeting", "name"));
    }
}
