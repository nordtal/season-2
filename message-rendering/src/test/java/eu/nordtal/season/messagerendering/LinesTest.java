package eu.nordtal.season.messagerendering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.context.MessageEnvironment;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

/** A text drawn where the game shows single lines: a tooltip's lines, a name, a title, a bar. */
class LinesTest {

    private static final MessageRenderer RENDER = new MessageRenderer(Messages.load("messages/render", Locale.ENGLISH)
            .within(new MessageEnvironment(Map.of(), ZoneId.of("UTC"), Tone::hex)));

    /** Renders {@code text} as an admin would try it for the key {@code greeting}. */
    private static Component render(final String text, final Object name) {
        return RENDER.format(
                Viewer.of(Locale.ENGLISH), new MessageRef("greeting", Map.of("name", name, "count", 2)), text);
    }

    /** The text a line shows, with the colour and decorations each of its characters is drawn in. */
    private static String drawn(final Component line) {
        final StringBuilder out = new StringBuilder();
        draw(line, line.style(), out);
        return out.toString();
    }

    private static void draw(
            final Component component, final net.kyori.adventure.text.format.Style inherited, final StringBuilder out) {
        final net.kyori.adventure.text.format.Style style = component
                .style()
                .merge(inherited, net.kyori.adventure.text.format.Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (component instanceof final TextComponent text && !text.content().isEmpty()) {
            final TextColor colour = style.color();
            out.append('[')
                    .append(colour == null ? "-" : colour.asHexString())
                    .append(style.decoration(TextDecoration.UNDERLINED) == TextDecoration.State.TRUE ? "_" : "")
                    .append(style.clickEvent() == null ? "" : "@")
                    .append(']')
                    .append(text.content());
        }
        component.children().forEach(child -> draw(child, style, out));
    }

    @Test
    void aLineBreakSplitsATextIntoLinesEachDrawnInTheStyleItStoodIn() {
        final List<Component> lines =
                Lines.of(render("<muted>Hand something in,<newline>and your share</muted> appears", "x"));
        final String muted = TextColor.fromHexString(Tone.MUTED.hex()).asHexString();
        assertEquals(
                List.of("[" + muted + "]Hand something in,", "[" + muted + "]and your share[-] appears"),
                lines.stream().map(LinesTest::drawn).toList());
    }

    @Test
    void aLineBreakInsideAValueSplitsTheLineToo() {
        final List<Component> lines = Lines.of(render("<bad>{name}</bad> here", "first\nsecond"));
        final String bad = TextColor.fromHexString(Tone.BAD.hex()).asHexString();
        assertEquals(
                List.of("[" + bad + "]first", "[" + bad + "]second[-] here"),
                lines.stream().map(LinesTest::drawn).toList());
    }

    @Test
    void aLinkKeepsItsClickAndUnderlineOnTheLineItEndsUpOn() {
        final List<Component> lines = Lines.of(render(
                "<muted>Discord:</muted><newline><click:open_url:'{name}'><u>{name}</u></click>",
                "https://nordtal.eu"));
        assertEquals(2, lines.size());
        assertEquals("[-_@]https://nordtal.eu", drawn(lines.get(1)));
        final Component link = lines.get(1).children().getFirst();
        assertEquals(ClickEvent.Action.OPEN_URL, link.style().clickEvent().action());
    }

    @Test
    void aTextOnOneLineIsLeftAsItIs() {
        final Component one = render("<muted>one line</muted>", "x");
        final List<Component> lines = Lines.of(one);
        assertEquals(1, lines.size());
        assertSame(one, lines.getFirst());
    }

    @Test
    void aPlaceThatDrawsOneLineShowsTheLinesSideBySide() {
        assertEquals("[-]first[-] [-]second", drawn(Lines.joined(render("first<newline>second", "x"))));
    }

    @Test
    void aTooltipIsHeadedByTheFirstLineOfItsNameAndTheRestOpenItsLines() {
        final List<Component> tooltip = Lines.tooltip(
                render("Heading<newline>under it", "x"),
                List.of(render("lore<newline>more", "x"), Component.text("last")));
        assertEquals(
                List.of("[-]Heading", "[-]under it", "[-]lore", "[-]more", "[-]last"),
                tooltip.stream().map(LinesTest::drawn).toList());
    }
}
