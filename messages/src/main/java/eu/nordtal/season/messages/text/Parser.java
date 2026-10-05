package eu.nordtal.season.messages.text;

import eu.nordtal.season.messages.CheckMessages;
import eu.nordtal.season.messages.MessageRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Reads a message text into nodes, one left-to-right pass; see {@link MessageText} for the syntax. */
final class Parser {

    private static final CheckMessages.Check.Syntax SAYS =
            CheckMessages.TEXTS.check().syntax();

    private final String text;
    private final boolean markup;
    private int at;

    private Parser(final String text, final boolean markup) {
        this.text = text;
        this.markup = markup;
    }

    static List<Node> parse(final String text, final boolean markup) {
        final Parser parser = new Parser(text, markup);
        final List<Node> nodes = parser.sequence(null, false);
        if (parser.at < text.length()) {
            throw fail(SAYS.closesNothing(parser.at + 1), parser.at);
        }
        return nodes;
    }

    /** Reads until the end, or until the {@code }} that closes a case. */
    private List<Node> sequence(final @Nullable String plural, final boolean inCase) {
        final List<Node> nodes = new ArrayList<>();
        final StringBuilder literal = new StringBuilder();
        while (at < text.length()) {
            final char c = text.charAt(at);
            if (c == '\\' && at + 1 < text.length() && "{}#\\<".indexOf(text.charAt(at + 1)) >= 0) {
                literal.append(text.charAt(at + 1));
                at += 2;
            } else if (c == '{') {
                flush(literal, nodes);
                nodes.add(placeholder(plural));
            } else if (c == '}') {
                if (!inCase) {
                    break;
                }
                flush(literal, nodes);
                return nodes;
            } else if (c == '#' && plural != null) {
                flush(literal, nodes);
                nodes.add(new Node.Pound(plural));
                at++;
            } else if (c == '<' && markup) {
                final Node.Tag tag = tag();
                if (tag == null) {
                    literal.append(c);
                    at++;
                } else {
                    flush(literal, nodes);
                    nodes.add(tag);
                }
            } else {
                literal.append(c);
                at++;
            }
        }
        if (inCase) {
            throw fail(SAYS.caseNeverClosed(at + 1), at);
        }
        flush(literal, nodes);
        return nodes;
    }

    private static void flush(final StringBuilder literal, final List<Node> into) {
        if (!literal.isEmpty()) {
            into.add(new Node.Literal(literal.toString()));
            literal.setLength(0);
        }
    }

    /** At an opening brace: a value, a plural or a select. */
    private Node placeholder(final @Nullable String plural) {
        final int start = at;
        at++;
        final String name = name();
        if (name.isEmpty()) {
            throw fail(SAYS.opensNothing(start + 1), start);
        }
        space();
        if (peek() == '}') {
            at++;
            return new Node.Value(name, null, null);
        }
        expect(',', SAYS.commaAfterName(at + 1));
        space();
        final String second = word();
        space();
        if ("plural".equals(second) || "select".equals(second)) {
            expect(',', SAYS.commaBeforeCases(at + 1));
            return choice(name, "plural".equals(second), plural, start);
        }
        if (second.isEmpty()) {
            throw fail(SAYS.kindMissing(name, at + 1), at);
        }
        String style = null;
        if (peek() == ',') {
            at++;
            space();
            style = word();
            space();
            if (style.isEmpty()) {
                throw fail(SAYS.styleMissing(name, second, at + 1), at);
            }
        }
        expect('}', SAYS.valueNotClosed(name, at + 1));
        return new Node.Value(name, second, style);
    }

    private Node.Choice choice(
            final String name, final boolean isPlural, final @Nullable String outer, final int start) {
        final Map<String, List<Node>> cases = new LinkedHashMap<>();
        while (true) {
            space();
            if (at >= text.length()) {
                throw fail(SAYS.choiceNeverClosed(name, start + 1), start);
            }
            if (peek() == '}') {
                at++;
                break;
            }
            final String key = caseKey();
            if (key.isEmpty()) {
                throw fail(SAYS.caseUnnamed(name, at + 1), at);
            }
            space();
            expect('{', SAYS.caseNotOpened(key, at + 1));
            if (cases.containsKey(key)) {
                throw fail(SAYS.caseTwice(name, key, at + 1), at);
            }
            cases.put(key, sequence(isPlural ? name : outer, true));
            at++;
        }
        if (!cases.containsKey("other")) {
            throw fail(SAYS.otherMissing(name, start + 1), start);
        }
        return new Node.Choice(name, isPlural, cases);
    }

    private String caseKey() {
        final int start = at;
        if (peek() == '=') {
            at++;
            while (at < text.length() && Character.isDigit(text.charAt(at))) {
                at++;
            }
            return text.substring(start, at);
        }
        return word();
    }

    /** At a {@code <}: a tag, or {@code null} when what follows is no tag and the character is text. */
    private Node.@Nullable Tag tag() {
        final int start = at;
        int cursor = at + 1;
        Node.Tag.Shape shape = Node.Tag.Shape.OPEN;
        if (cursor < text.length() && text.charAt(cursor) == '/') {
            shape = Node.Tag.Shape.CLOSE;
            cursor++;
        }
        final int nameStart = cursor;
        if (cursor < text.length() && "#!?".indexOf(text.charAt(cursor)) >= 0) {
            cursor++;
        }
        while (cursor < text.length() && isTagNameChar(text.charAt(cursor))) {
            cursor++;
        }
        if (cursor == nameStart || cursor >= text.length()) {
            return null;
        }
        final String name = text.substring(nameStart, cursor).toLowerCase(Locale.ROOT);
        at = cursor;
        final List<Node.Tag.Arg> args = new ArrayList<>();
        while (at < text.length() && text.charAt(at) == ':') {
            at++;
            final Node.Tag.Arg arg = argument();
            if (arg == null) {
                at = start;
                return null;
            }
            args.add(arg);
        }
        if (at < text.length() && text.charAt(at) == '/' && shape == Node.Tag.Shape.OPEN) {
            shape = Node.Tag.Shape.SELF_CLOSING;
            at++;
        }
        if (at >= text.length() || text.charAt(at) != '>') {
            at = start;
            return null;
        }
        at++;
        return new Node.Tag(name, args, shape);
    }

    private static boolean isTagNameChar(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    /** One tag argument after its {@code :}, or {@code null} when the tag never closes. */
    private Node.Tag.@Nullable Arg argument() {
        final char quote =
                at < text.length() && (text.charAt(at) == '\'' || text.charAt(at) == '"') ? text.charAt(at) : 0;
        if (quote != 0) {
            at++;
        }
        final List<Node> parts = new ArrayList<>();
        final StringBuilder literal = new StringBuilder();
        while (at < text.length()) {
            final char c = text.charAt(at);
            if (quote != 0 && c == '\\' && at + 1 < text.length()) {
                final char next = text.charAt(at + 1);
                literal.append(next == '{' || next == '}' ? "" : "\\").append(next);
                at += 2;
                continue;
            }
            if (quote != 0 && c == quote) {
                at++;
                flush(literal, parts);
                return new Node.Tag.Arg(parts, quote);
            }
            final boolean selfClosing = c == '/' && at + 1 < text.length() && text.charAt(at + 1) == '>';
            if (quote == 0 && (c == ':' || c == '>' || selfClosing)) {
                flush(literal, parts);
                return new Node.Tag.Arg(parts, quote);
            }
            if (c == '{') {
                flush(literal, parts);
                final int start = at;
                final Node value = placeholder(null);
                if (!(value instanceof Node.Value)) {
                    throw fail(SAYS.choiceInArgument(start + 1), start);
                }
                parts.add(value);
                continue;
            }
            literal.append(c);
            at++;
        }
        return null;
    }

    private String name() {
        final int start = at;
        while (at < text.length()) {
            final char c = text.charAt(at);
            final boolean letter = Character.isLetter(c) || c == '_';
            final boolean inner = letter || Character.isDigit(c) || c == '-' || c == '.';
            if (at == start ? !letter : !inner) {
                break;
            }
            at++;
        }
        final String name = text.substring(start, at);
        if (name.endsWith(".") || name.contains("..")) {
            throw fail(SAYS.attributeMissing(name, start + 1), start);
        }
        return name;
    }

    private String word() {
        final int start = at;
        while (at < text.length()
                && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '-' || text.charAt(at) == '_')) {
            at++;
        }
        return text.substring(start, at);
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }

    private char peek() {
        return at < text.length() ? text.charAt(at) : 0;
    }

    /** Steps over {@code c}, or refuses with {@code otherwise}, which names the character where it is missing. */
    private void expect(final char c, final MessageRef otherwise) {
        if (peek() != c) {
            throw fail(otherwise, at);
        }
        at++;
    }

    private static MessageSyntaxException fail(final MessageRef reason, final int position) {
        return new MessageSyntaxException(reason, position);
    }
}
