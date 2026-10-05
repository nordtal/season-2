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
import java.util.Objects;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.jspecify.annotations.Nullable;

/**
 * The Discord target: every text the bot sends, from the same prepared pieces as every other target.
 * In markdown each value is escaped, a moment is Discord's timestamp and a member a mention; a plain text takes
 * every value as it is. The README's "Texts" says why.
 */
public final class DiscordRenderer {

    private final Messages messages;

    private DiscordRenderer(final Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /** Returns the Discord target over {@code messages}. */
    public static DiscordRenderer of(final Messages messages) {
        return new DiscordRenderer(messages);
    }

    /** Returns the bundles behind this renderer, which the overrides are layered on. */
    public Messages raw() {
        return messages;
    }

    /** Renders a message for a member who reads {@code locale}. */
    public String format(final @Nullable Locale locale, final MessageRef message) {
        return render(messages.prepare(Viewer.of(locale), message));
    }

    /**
     * Renders a text an admin is trying for a message's key, in place of the key's own, with the message's values.
     *
     * @throws eu.nordtal.season.messages.text.MessageSyntaxException when the text cannot be read
     */
    public String format(final @Nullable Locale locale, final MessageRef message, final String text) {
        return render(messages.prepare(Viewer.of(locale), message, text));
    }

    /** Renders prepared pieces as Discord shows them. */
    public static String render(final Messages.Prepared prepared) {
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

    /**
     * Makes text inert for Discord's markdown, a backslash included, every single character and not only pairs.
     * Every value the bot places in markdown is escaped here, the embeds' own lines too.
     */
    public static String escape(final String text) {
        return MarkdownSanitizer.escape(text.replace("\\", "\\\\"), true);
    }

    private static String value(
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
            case GLYPH -> "";
            default -> text(kind, value, style, prepared, markdown);
        };
    }

    /** The one formatter's text, escaped in markdown; a link stays as it is, since a backslash would end up in it. */
    private static String text(
            final Kind kind,
            final Object value,
            final @Nullable String style,
            final Messages.Prepared prepared,
            final boolean markdown) {
        final String shown = ValueText.of(kind, value, style, prepared.language(), prepared.zone(), prepared.words());
        return markdown && !(kind == Kind.TEXT && link(shown)) ? escape(shown) : shown;
    }

    private static String list(
            final List<?> items, final boolean or, final Messages.Prepared prepared, final boolean markdown) {
        final List<String> shown = new ArrayList<>(items.size());
        for (final Object item : items) {
            // As every target lists: an item of no kind, or a list inside the list, as its text.
            final Kind kind = Kind.ofValue(item).orElse(Kind.TEXT);
            shown.add(
                    kind == Kind.LIST
                            ? value(Kind.TEXT, String.valueOf(item), null, prepared, markdown)
                            : value(kind, item, null, prepared, markdown));
        }
        return ValueText.join(shown, or, prepared.words());
    }

    /** Discord's timestamp, drawn in each reader's zone and language: {@code <t:1790000000:f>}. */
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
