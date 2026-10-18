package eu.nordtal.season.discordbot;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.spec.TextFormat;
import eu.nordtal.season.messages.text.Piece;
import eu.nordtal.season.messages.text.Tags;
import eu.nordtal.season.messages.value.Kind;
import eu.nordtal.season.messages.value.Mention;
import eu.nordtal.season.messages.value.ValueText;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.jspecify.annotations.Nullable;

public final class DiscordRenderer {

    private final Messages messages;

    private DiscordRenderer(final Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    public static DiscordRenderer of(final Messages messages) {
        return new DiscordRenderer(messages);
    }

    public Messages raw() {
        return messages;
    }

    public String format(final @Nullable Locale locale, final MessageRef message) {
        return render(messages.prepare(Viewer.of(locale), message));
    }

    /**
     * Renders {@code text} in place of the message key's own text.
     * An admin is trying that text. It is filled with the message's values.
     *
     * @throws eu.nordtal.season.messages.text.MessageSyntaxException when the text cannot be read
     */
    public String format(final @Nullable Locale locale, final MessageRef message, final String text) {
        return render(messages.prepare(Viewer.of(locale), message, text));
    }

    /**
     * Renders a text that no key holds, such as a document's, with its values.
     *
     * @param kinds each value's declared kind
     * @param source names the text in the log when a value is missing
     */
    public String format(
            final @Nullable Locale locale,
            final String text,
            final Map<String, ?> values,
            final Map<String, Kind> kinds,
            final TextFormat format,
            final String source) {
        return render(messages.prepare(Viewer.of(locale), text, values, kinds, format, source));
    }

    private String render(final Messages.Prepared prepared) {
        final boolean markdown = prepared.format() == TextFormat.DISCORD_MARKDOWN;
        final StringBuilder out = new StringBuilder();
        for (final Piece piece : prepared.pieces()) {
            switch (piece) {
                case Piece.Text text -> out.append(text.text());
                case Piece.Filled filled ->
                    out.append(value(filled.kind(), filled.value(), filled.style(), prepared, markdown));
                case Piece.Markup markup -> {
                    if (Tags.isLineBreak(markup.tag().name())) {
                        out.append('\n');
                    }
                }
            }
        }
        return out.toString();
    }

    public static String escape(final String text) {
        return MarkdownSanitizer.escape(text.replace("\\", "\\\\"), true);
    }

    private String value(
            final Kind kind,
            final @Nullable Object value,
            final @Nullable String style,
            final Messages.Prepared prepared,
            final boolean markdown) {
        if (value == null) {
            return prepared.words().missing(kind);
        }
        return switch (kind) {
            case INSTANT -> markdown ? timestamp((Instant) value, style) : text(kind, value, style, prepared, false);
            case MENTION ->
                markdown ? "<@" + ((Mention) value).member().value() + ">" : text(kind, value, style, prepared, false);
            case LIST -> list((List<?>) value, "or".equals(style), prepared, markdown);
            case MESSAGE -> markdown ? nested((MessageRef) value, prepared) : text(kind, value, style, prepared, false);
            case GLYPH -> "";
            default -> text(kind, value, style, prepared, markdown);
        };
    }

    /** Escapes the text in markdown, but a link stays as it is, because a backslash would end up in it. */
    private static String text(
            final Kind kind,
            final Object value,
            final @Nullable String style,
            final Messages.Prepared prepared,
            final boolean markdown) {
        final String shown = ValueText.of(kind, value, style, prepared.language(), prepared.zone(), prepared.words());
        return markdown && !(kind == Kind.TEXT && link(shown)) ? escape(shown) : shown;
    }

    private String nested(final MessageRef message, final Messages.Prepared outer) {
        final Messages.Prepared inner = messages.prepare(new Viewer(outer.language(), outer.zone(), null), message);
        final String shown = render(inner);
        return inner.format() == TextFormat.DISCORD_MARKDOWN ? shown : escape(shown);
    }

    private String list(
            final List<?> items, final boolean or, final Messages.Prepared prepared, final boolean markdown) {
        final List<String> shown = new ArrayList<>(items.size());
        for (final Object item : items) {
            final Kind kind = Kind.ofValue(item).orElse(Kind.TEXT);
            shown.add(
                    kind == Kind.LIST
                            ? value(Kind.TEXT, String.valueOf(item), null, prepared, markdown)
                            : value(kind, item, null, prepared, markdown));
        }
        return ValueText.join(shown, or, prepared.words());
    }

    private static String timestamp(final Instant instant, final @Nullable String style) {
        final char shape = switch (style == null ? "datetime" : style) {
            case "date" -> 'D';
            case "time" -> 't';
            case "relative" -> 'R';
            default -> 'f';
        };
        return "<t:" + instant.getEpochSecond() + ":" + shape + ">";
    }

    private static boolean link(final String text) {
        return (text.startsWith("https://") || text.startsWith("http://"))
                && text.chars().noneMatch(Character::isWhitespace);
    }
}
