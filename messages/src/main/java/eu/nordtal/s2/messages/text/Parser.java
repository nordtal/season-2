package eu.nordtal.s2.messages.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Reads a message text into nodes, one left-to-right pass; see {@link MessageText} for the syntax. */
final class Parser {

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
            throw parser.fail("a } closes nothing; write \\} for the character");
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
            throw fail("a case of a plural or select is never closed with }");
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
            throw fail("a { opens no placeholder; write \\{ for the character", start);
        }
        space();
        if (peek() == '}') {
            at++;
            return new Node.Value(name, null, null);
        }
        expect(',', "after the placeholder's name");
        space();
        final String second = word();
        space();
        if ("plural".equals(second) || "select".equals(second)) {
            expect(',', "before the cases");
            return choice(name, "plural".equals(second), plural, start);
        }
        if (second.isEmpty()) {
            throw fail("a kind is missing after " + name + ",");
        }
        String style = null;
        if (peek() == ',') {
            at++;
            space();
            style = word();
            space();
            if (style.isEmpty()) {
                throw fail("a style is missing after " + name + ", " + second + ",");
            }
        }
        expect('}', "to close {" + name);
        return new Node.Value(name, second, style);
    }

    private Node.Choice choice(
            final String name, final boolean isPlural, final @Nullable String outer, final int start) {
        final Map<String, List<Node>> cases = new LinkedHashMap<>();
        while (true) {
            space();
            if (at >= text.length()) {
                throw fail("{" + name + " is never closed", start);
            }
            if (peek() == '}') {
                at++;
                break;
            }
            final String key = caseKey();
            if (key.isEmpty()) {
                throw fail("a case of {" + name + "} needs a name before its {");
            }
            space();
            expect('{', "to open the case " + key);
            if (cases.containsKey(key)) {
                throw fail("{" + name + "} has the case " + key + " twice");
            }
            cases.put(key, sequence(isPlural ? name : outer, true));
            at++;
        }
        if (!cases.containsKey("other")) {
            throw fail("{" + name + "} needs an other case, for whatever no case names", start);
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
                    throw fail("a plural or select cannot stand inside a tag's argument", start);
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
            throw fail("{" + name + " names no attribute after its dot", start);
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

    private void expect(final char c, final String where) {
        if (peek() != c) {
            throw fail("expected " + c + " " + where);
        }
        at++;
    }

    private MessageSyntaxException fail(final String message) {
        return fail(message, at);
    }

    private static MessageSyntaxException fail(final String message, final int position) {
        return new MessageSyntaxException(message, position);
    }
}
