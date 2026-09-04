package eu.nordtal.s2.common.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

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
    @DisplayName("a component value arrives as a component, not as its text")
    void componentValuesKeepTheirStyle() {
        final Component rendered = RENDER.format(Locale.ENGLISH, "composed",
                Map.of("line", Component.text("mined a stone").color(NamedTextColor.GREEN)),
                "who", "Ida");

        assertEquals("Ida says mined a stone", plain(rendered));
        assertTrue(green(rendered), "the component value lost its colour on the way in - which is"
                + " what happens when it is flattened to a String and substituted");
    }

    /**
     * The reason the overload exists at all: vanilla's death message and an advancement's title are
     * {@code TranslatableComponent}s, and every reader's own client renders them in that reader's
     * language. A trip through {@code String} would settle the language on the server, once, for
     * everybody.
     */
    @Test
    @DisplayName("a translatable value stays translatable")
    void aTranslatableValueSurvives() {
        final Component rendered = RENDER.format(Locale.ENGLISH, "composed",
                Map.of("line", Component.translatable("death.attack.lava")), "who", "Ida");

        assertTrue(contains(rendered, TranslatableComponent.class),
                "the client has to be the one that translates a death message, so the component has"
                        + " to reach it as a translatable and not as English text");
    }

    /**
     * A component value is not escaped and must not need to be - it never meets the parser. What is
     * still escaped is the ordinary {@code {who}} beside it, and this pins that the two kinds do not
     * contaminate each other.
     */
    @Test
    @DisplayName("a component value beside a hostile text value is still safe")
    void theTwoKindsOfValueDoNotMix() {
        final Component rendered = RENDER.format(Locale.ENGLISH, "composed",
                Map.of("line", Component.text("hello")), "who", "<red>Mallory");

        assertEquals("<red>Mallory says hello", plain(rendered));
    }

    private static boolean green(final Component component) {
        if (NamedTextColor.GREEN.equals(component.color())) {
            return true;
        }
        return component.children().stream().anyMatch(MessageRendererTest::green);
    }

    private static boolean contains(final Component component, final Class<?> type) {
        if (type.isInstance(component)) {
            return true;
        }
        return component.children().stream().anyMatch(child -> contains(child, type));
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
    @DisplayName("a backslash in a value cannot unescape the tag behind it")
    void aBackslashCannotUnescapeTheNextTag() {
        // The attack is one character long. Escaping only '<' turns  \<red>  into  \\<red> , and
        // MiniMessage reads  \\  as one literal backslash - so the '<' it was protecting arrives at
        // the parser unguarded and the tag fires. The value below is the shape that matters: a
        // click tag runs a command as whoever reads the message.
        final Component rendered = RENDER.format(Locale.ENGLISH, "greeting",
                "name", "\\<click:run_command:'/kill @a'>gift</click>", "count", 0);

        assertEquals("Hello \\<click:run_command:'/kill @a'>gift</click>, you have 0 left",
                plain(rendered),
                "a backslash before a tag let the tag through - escape('\\\\') has to run before"
                        + " escape('<')");
        assertTrue(rendered.clickEvent() == null && !hasClickEvent(rendered),
                "the value produced a click event, which is the whole point of the escape");
    }

    @Test
    @DisplayName("a lone backslash survives as a lone backslash")
    void aBackslashIsNotDoubled() {
        assertEquals("Hello back\\slash, you have 0 left",
                plain(RENDER.format(Locale.ENGLISH, "greeting", "name", "back\\slash", "count", 0)),
                "escaping the escape character must be invisible once MiniMessage has parsed it");
    }

    /** @return whether {@code component} or any of its children carries a click event */
    private static boolean hasClickEvent(final Component component) {
        if (component.clickEvent() != null) {
            return true;
        }
        return component.children().stream().anyMatch(MessageRendererTest::hasClickEvent);
    }

    @Test
    @DisplayName("an odd parameter count is refused rather than silently dropping one")
    void oddParametersAreRefused() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> RENDER.format(Locale.ENGLISH, "greeting", "name"));
    }
}
