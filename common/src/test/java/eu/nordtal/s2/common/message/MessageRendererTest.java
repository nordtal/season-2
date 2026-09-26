package eu.nordtal.s2.common.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

/**
 * The rules {@link MessageRenderer} exists to keep.
 *
 * A template is controlled by the repository, but a placeholder value is a player name; see
 * {@link #aValueContainingATagCannotInjectMinimessage()}.
 */
class MessageRendererTest {

    private static final MessageRenderer RENDER = new MessageRenderer(Messages.load("messages/render", Locale.ENGLISH));

    /** Flattens a component to its text without the separate plain-text serializer artifact. */
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
    void aMessageWithNoTagsRendersAsItsOwnText() {
        assertEquals("Nothing to parse here", plain(RENDER.get(Locale.ENGLISH, "plain")));
    }

    @Test
    void tagsInAMessageAreParsedNotShown() {
        final Component rendered = RENDER.get(Locale.ENGLISH, "tagged");
        assertEquals("danger and calm", plain(rendered));
        assertTrue(
                rendered.children().stream().anyMatch(child -> NamedTextColor.RED.equals(child.color())),
                "the <red> tag did not become a colour");
    }

    @Test
    void aGlyphTagNamesAGlyphAndDrawsItInTheDefaultFont() {
        final Component rendered = RENDER.get(Locale.ENGLISH, "glyph");
        assertEquals(eu.nordtal.s2.common.Glyphs.TAG_ADMIN + " Admin", plain(rendered));
        assertTrue(
                fonts(rendered).contains(net.kyori.adventure.key.Key.key("minecraft", "default")),
                "the glyph named no font, so a surrounding font would draw something else there");
    }

    @Test
    void aGlyphTagWithANameNobodyKnowsStaysVisibleAsText() {
        assertEquals("<glyph:nope> here", plain(RENDER.get(Locale.ENGLISH, "glyph-unknown")));
    }

    @Test
    void aValueCannotDrawAGlyph() {
        assertEquals(
                "Hello <glyph:admin>", plain(RENDER.format(Locale.ENGLISH, "glyph-in-value", "name", "<glyph:admin>")));
    }

    private static java.util.Set<net.kyori.adventure.key.Key> fonts(final Component component) {
        final java.util.Set<net.kyori.adventure.key.Key> out = new java.util.HashSet<>();
        if (component.font() != null) {
            out.add(component.font());
        }
        component.children().forEach(child -> out.addAll(fonts(child)));
        return out;
    }

    @Test
    void aComponentValueArrivesAsAComponentNotAsItsText() {
        final Component rendered = RENDER.format(
                Locale.ENGLISH,
                "composed",
                Map.of("line", Component.text("mined a stone").color(NamedTextColor.GREEN)),
                "who",
                "Ida");

        assertEquals("Ida says mined a stone", plain(rendered));
        assertTrue(
                green(rendered),
                "the component value lost its colour on the way in - which is"
                        + " what happens when it is flattened to a String and substituted");
    }

    /** Checks that a translatable value stays translatable, so each client renders it in its own language. */
    @Test
    void aTranslatableValueStaysTranslatable() {
        final Component rendered = RENDER.format(
                Locale.ENGLISH, "composed", Map.of("line", Component.translatable("death.attack.lava")), "who", "Ida");

        assertTrue(
                contains(rendered, TranslatableComponent.class),
                "the client has to be the one that translates a death message, so the component has"
                        + " to reach it as a translatable and not as English text");
    }

    /** Checks that a component value is not escaped while a string value beside it still is. */
    @Test
    void aComponentValueBesideAHostileTextValueIsStillSafe() {
        final Component rendered = RENDER.format(
                Locale.ENGLISH, "composed", Map.of("line", Component.text("hello")), "who", "<red>Mallory");

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
    void placeholdersAreSubstitutedBeforeParsing() {
        assertEquals(
                "Hello Till, you have 3 left",
                plain(RENDER.format(Locale.ENGLISH, "greeting", "name", "Till", "count", 3)));
    }

    @Test
    void aValueContainingATagCannotInjectMinimessage() {
        final Component rendered = RENDER.format(Locale.ENGLISH, "greeting", "name", "<red>evil</red>", "count", 0);
        assertEquals(
                "Hello <red>evil</red>, you have 0 left",
                plain(rendered),
                "a player whose name contains MiniMessage syntax coloured the message");
    }

    @Test
    void aBackslashInAValueCannotUnescapeTheTagBehindIt() {
        // Escaping only '<' turns \<red> into \\<red>, which MiniMessage reads as a backslash and a live tag.
        final Component rendered = RENDER.format(
                Locale.ENGLISH, "greeting", "name", "\\<click:run_command:'/kill @a'>gift</click>", "count", 0);

        assertEquals(
                "Hello \\<click:run_command:'/kill @a'>gift</click>, you have 0 left",
                plain(rendered),
                "a backslash before a tag let the tag through - escape('\\\\') has to run before" + " escape('<')");
        assertTrue(
                rendered.clickEvent() == null && !hasClickEvent(rendered),
                "the value produced a click event, which is the whole point of the escape");
    }

    @Test
    void aLoneBackslashSurvivesAsALoneBackslash() {
        assertEquals(
                "Hello back\\slash, you have 0 left",
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
    void anOddParameterCountIsRefusedRatherThanSilentlyDroppingOne() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> RENDER.format(Locale.ENGLISH, "greeting", "name"));
    }

    @Test
    void aWholeMapPassedAsTheParametersSaysSoInsteadOfCountingToOne() {
        // A Map is an Object, so format(locale, key, map) compiles and must be refused.
        final IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> RENDER.format(Locale.ENGLISH, "greeting", (Object) Map.of("name", "Till")));

        assertTrue(refused.getMessage().contains("Map"), refused.getMessage());
        assertTrue(refused.getMessage().contains("Flatten"), refused.getMessage());
    }

    @Test
    void anEmptyMapIsCaughtTooWhichIsTheCaseThatHidTheBug() {
        // The empty map is the case that looks safest.
        final IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> RENDER.format(Locale.ENGLISH, "greeting", (Object) Map.of()));

        assertTrue(refused.getMessage().contains("Map"), refused.getMessage());
    }
}
