package eu.nordtal.season.messages.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The one syntax: placeholders, plural and select, tags, escapes. */
class MessageTextTest {

    private static List<Node> parse(final String text) {
        return MessageText.parse(text, true).nodes();
    }

    @Test
    void aPlaceholderCarriesItsKindAndStyle() {
        assertEquals(
                List.of(new Node.Literal("in "), new Node.Value("left", "duration", "short")),
                parse("in {left, duration, short}"));
        assertEquals(List.of(new Node.Value("winner.name", null, null)), parse("{winner.name}"));
    }

    @Test
    void aPluralKeepsItsCasesAndItsPound() {
        final Node.Choice choice = assertInstanceOf(
                Node.Choice.class, parse("{n, plural, =0 {none} other {# x}}").getFirst());
        assertEquals(List.of("=0", "other"), List.copyOf(choice.cases().keySet()));
        assertEquals(
                List.of(new Node.Pound("n"), new Node.Literal(" x")),
                choice.cases().get("other"));
    }

    @Test
    void anEscapedBraceIsText() {
        assertEquals(List.of(new Node.Literal("{not} a value")), parse("\\{not\\} a value"));
    }

    @Test
    void aTagKeepsItsArgumentsAndAValueInOne() {
        final Node.Tag tag = assertInstanceOf(
                Node.Tag.class, parse("<hover:show_text:'hi {name}'>").getFirst());
        assertEquals("hover", tag.name());
        assertEquals("show_text", tag.args().getFirst().literal());
        assertEquals(
                List.of(new Node.Literal("hi "), new Node.Value("name", null, null)),
                tag.args().get(1).parts());
    }

    @Test
    void withoutMarkupALessThanIsText() {
        assertEquals(
                List.of(new Node.Literal("a <b> c")),
                MessageText.parse("a <b> c", false).nodes());
    }

    @Test
    void aBrokenPlaceholderIsASyntaxError() {
        assertThrows(MessageSyntaxException.class, () -> parse("{name"));
        assertThrows(MessageSyntaxException.class, () -> parse("name}"));
        assertThrows(MessageSyntaxException.class, () -> parse("{winner.}"));
        assertThrows(MessageSyntaxException.class, () -> parse("{n, plural, one {x}}"));
        assertThrows(MessageSyntaxException.class, () -> parse("{n, plural, one {x} one {y} other {z}}"));
    }
}
