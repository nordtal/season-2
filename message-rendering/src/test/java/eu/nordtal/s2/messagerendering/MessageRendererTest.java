package eu.nordtal.s2.messagerendering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.Tone;
import eu.nordtal.s2.messages.context.MessageEnvironment;
import eu.nordtal.s2.messages.value.Action;
import eu.nordtal.s2.messages.value.DisplayName;
import eu.nordtal.s2.messages.value.GameContent;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The rules the Minecraft target exists to keep.
 * A text is the repository's or an admin's, but a value is a player name; see
 * {@link #aValueContainingATagCannotInjectMinimessage()}.
 */
class MessageRendererTest {

    private static final DisplayName ALEX =
            new DisplayName(PlayerId.of(UUID.fromString("00000000-0000-0000-0000-00000000000a")), "Alex");

    /** Draws a name with a card naming the player, as a server's composition would. */
    private static final Names CARDED =
            (name, reader) -> Component.text(name.name()).hoverEvent(Component.text("card of " + name.name()));

    private static final MessageRenderer RENDER = new MessageRenderer(
            Messages.load("messages/render", Locale.ENGLISH)
                    .within(new MessageEnvironment(
                            Map.of(), ZoneId.of("UTC"), tone -> tone == Tone.BAD ? "#ff0000" : tone.hex())),
            CARDED);

    private static Component render(final String key, final Map<String, Object> args) {
        return RENDER.format(Locale.ENGLISH, new MessageRef(key, args));
    }

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

    private static @Nullable Component find(
            final Component component, final java.util.function.Predicate<Component> test) {
        if (test.test(component)) {
            return component;
        }
        for (final Component child : component.children()) {
            final Component found = find(child, test);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void aMessageWithNoTagsRendersAsItsOwnText() {
        assertEquals("Nothing to parse here", plain(render("plain", Map.of())));
    }

    @Test
    void aToneIsPaintedFromThePaletteAsItIsNow() {
        final Component rendered = render("toned", Map.of());
        assertEquals("danger and calm", plain(rendered));
        assertNotNull(find(rendered, child -> TextColor.fromHexString("#ff0000").equals(child.color())));
        assertNotNull(find(
                rendered, child -> TextColor.fromHexString(Tone.MUTED.hex()).equals(child.color())));
    }

    @Test
    void aGlyphTagWithANameNobodyKnowsStaysVisibleAsText() {
        assertEquals("<glyph:nope> here", plain(render("glyph-unknown", Map.of())));
    }

    @Test
    void aValueContainingATagCannotInjectMinimessage() {
        final Component rendered = render("greeting", Map.of("name", "<red>evil</red><glyph:admin>", "count", 0));
        assertEquals("Hello <red>evil</red><glyph:admin>, you have 0 left", plain(rendered));
        assertNull(find(rendered, child -> child.color() != null));
    }

    @Test
    void aBackslashInAValueCannotUnescapeTheTagBehindIt() {
        final Component rendered =
                render("greeting", Map.of("name", "\\<click:run_command:'/kill @a'>gift</click>", "count", 0));
        assertEquals("Hello \\<click:run_command:'/kill @a'>gift</click>, you have 0 left", plain(rendered));
        assertNull(find(rendered, child -> child.clickEvent() != null));
        assertEquals(
                "Hello back\\slash, you have 3 left",
                plain(render("greeting", Map.of("name", "back\\slash", "count", 3))));
    }

    @Test
    void gameContentStaysTranslatableSoTheClientNamesItInItsOwnLanguage() {
        final Component rendered = render("found", Map.of("item", GameContent.of("item.minecraft.diamond_sword")));
        final Component item = find(rendered, TranslatableComponent.class::isInstance);
        assertNotNull(item);
        assertEquals("item.minecraft.diamond_sword", ((TranslatableComponent) item).key());
        assertEquals("Diamond Sword", ((TranslatableComponent) item).fallback());
    }

    @Test
    void aGameLineKeepsItsArgumentsAndDrawsAPlayerInItAsThisProcessDrawsNames() {
        final GameContent line = new GameContent(
                "death.attack.player",
                "%1$s was slain by %2$s",
                java.util.List.of(ALEX, GameLines.of(Component.translatable("entity.minecraft.zombie", "Zombie"))));
        final TranslatableComponent rendered = (TranslatableComponent)
                find(render("found", Map.of("item", line)), TranslatableComponent.class::isInstance);

        assertNotNull(rendered);
        assertEquals(2, rendered.arguments().size());
        assertNotNull(rendered.arguments().get(0).asComponent().hoverEvent(), "the player is drawn by Names");
        assertEquals(
                "entity.minecraft.zombie",
                ((TranslatableComponent) rendered.arguments().get(1).asComponent()).key());
    }

    @Test
    void aValueInAHoverTextIsAComponentAndNeverMarkup() {
        final Component rendered = render("hover", Map.of("reason", "<red>it is late"));
        final Component hovered = find(rendered, child -> child.hoverEvent() != null);
        assertNotNull(hovered);
        final HoverEvent<?> hover = hovered.hoverEvent();
        assertEquals("Because <red>it is late", plain((Component) hover.value()));
    }

    @Test
    void aValueInAClickTargetCannotEndItsArgument() {
        final Component rendered = render("reply", Map.of("name", "Al'ex"));
        final Component clicked = find(rendered, child -> child.clickEvent() != null);
        assertNotNull(clicked);
        assertEquals(
                "/msg Al'ex ", ((ClickEvent.Payload.Text) clicked.clickEvent().payload()).value());
    }

    @Test
    void anActionRunsTheCommandTheCodeBoundToIt() {
        final Component rendered = render("offer", Map.of("accept", new Action("/accept 7")));
        assertEquals("[Accept]", plain(rendered));
        final Component clicked = find(rendered, child -> child.clickEvent() != null);
        assertNotNull(clicked);
        assertEquals(ClickEvent.Action.RUN_COMMAND, clicked.clickEvent().action());
        assertEquals(
                "/accept 7", ((ClickEvent.Payload.Text) clicked.clickEvent().payload()).value());
    }

    @Test
    void aDisplayNameCarriesItsCardUnlessItsStyleIsPlain() {
        final Component rendered = render("who", Map.of("who", ALEX, "who2", ALEX));
        assertEquals("Alex and Alex", plain(rendered));
        assertEquals(
                1,
                rendered.children().stream()
                                .filter(child -> child.hoverEvent() != null)
                                .count()
                        + (rendered.hoverEvent() == null ? 0 : 1));
        final Component carded = find(rendered, child -> child.hoverEvent() != null);
        assertNotNull(carded);
        assertEquals("card of Alex", plain((Component) carded.hoverEvent().value()));
    }

    @Test
    void aMissingValueShowsItsReplacementWord() {
        assertTrue(plain(render("greeting", Map.of("count", 1))).startsWith("Hello something,"));
    }
}
