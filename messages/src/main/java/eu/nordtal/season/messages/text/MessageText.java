package eu.nordtal.season.messages.text;

import java.util.List;
import java.util.Objects;

/** One text of a bundle, parsed once. The messages README describes the syntax.
 * A MiniMessage text reads its tags as structure, so a value inside a tag's argument is never spliced into markup
 * unescaped. Every other format keeps {@code <} as a character.
 */
public final class MessageText {

    private final String source;
    private final boolean markup;
    private final List<Node> nodes;

    private MessageText(final String source, final boolean markup, final List<Node> nodes) {
        this.source = source;
        this.markup = markup;
        this.nodes = nodes;
    }

    /**
     * @param markup whether the text is MiniMessage, whose tags are read as tags
     * @throws MessageSyntaxException when the text cannot be read
     */
    public static MessageText parse(final String source, final boolean markup) {
        Objects.requireNonNull(source, "source");
        return new MessageText(source, markup, List.copyOf(Parser.parse(source, markup)));
    }

    /** Returns a text that shows {@code source} as it is, for a text that could not be read. */
    public static MessageText verbatim(final String source, final boolean markup) {
        return new MessageText(source, markup, List.of(new Node.Literal(source)));
    }

    public String source() {
        return source;
    }

    public boolean markup() {
        return markup;
    }

    public List<Node> nodes() {
        return nodes;
    }

    @Override
    public String toString() {
        return source;
    }
}
