package eu.nordtal.season.messagerendering;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.Palette;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.spec.TextFormat;
import eu.nordtal.season.messages.text.Node;
import eu.nordtal.season.messages.text.Piece;
import eu.nordtal.season.messages.text.PlainText;
import eu.nordtal.season.messages.text.Tags;
import eu.nordtal.season.messages.value.Action;
import eu.nordtal.season.messages.value.DisplayName;
import eu.nordtal.season.messages.value.GameContent;
import eu.nordtal.season.messages.value.Glyph;
import eu.nordtal.season.messages.value.Kind;
import eu.nordtal.season.messages.value.ValueText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jspecify.annotations.Nullable;

/** Renders a message as an Adventure component.
 * A tag inserts every value as a component, so a value never opens a tag.
 */
public final class MessageRenderer {

    private static final String VALUE_TAG = "nordtal-value";

    /** Marks where a list's leading items and its last item go in the joining text. */
    private static final char REST_MARK = '\u0001';

    private static final char LAST_MARK = '\u0002';

    private static final List<GlyphNames> GLYPHS = load(GlyphNames.class);

    private static final TagResolver GLYPH_TAG = TagResolver.resolver("glyph", (arguments, context) -> {
        final String name = arguments.popOr("a glyph needs a name").value();
        final Component glyph = glyph(name);
        if (glyph == null) {
            throw context.newException("no glyph is named " + name, arguments);
        }
        return Tag.selfClosingInserting(glyph);
    });

    private final Messages messages;
    private final Names names;
    private final NameCards cards;

    public MessageRenderer(final Messages messages) {
        this(messages, Names.BARE, NameCards.NONE);
    }

    public MessageRenderer(final Messages messages, final Names names) {
        this(messages, names, NameCards.NONE);
    }

    public MessageRenderer(final Messages messages, final Names names, final NameCards cards) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.names = Objects.requireNonNull(names, "names");
        this.cards = Objects.requireNonNull(cards, "cards");
    }

    /** Returns a renderer that shows a player's bare name. */
    public static MessageRenderer of(final Messages messages) {
        return new MessageRenderer(messages);
    }

    public static MessageRenderer of(final Messages messages, final Names names) {
        return new MessageRenderer(messages, names);
    }

    public static MessageRenderer of(final Messages messages, final Names names, final NameCards cards) {
        return new MessageRenderer(messages, names, cards);
    }

    /** Returns a renderer with bare names and no cards. */
    public MessageRenderer bare() {
        return new MessageRenderer(messages);
    }

    public Messages raw() {
        return messages;
    }

    public Component format(final @Nullable Locale locale, final MessageRef message) {
        return format(Viewer.of(locale), message);
    }

    public Component format(final Viewer viewer, final MessageRef message) {
        return format(viewer, message, true);
    }

    /**
     * @throws eu.nordtal.season.messages.text.MessageSyntaxException when the text cannot be read
     */
    public Component format(final Viewer viewer, final MessageRef message, final String text) {
        return format(viewer, message, text, messages.environment().palette());
    }

    /**
     * @throws eu.nordtal.season.messages.text.MessageSyntaxException when the text cannot be read
     */
    public Component format(final Viewer viewer, final MessageRef message, final String text, final Palette palette) {
        return render(viewer, messages.prepare(viewer, message, text), message.args(), true, palette);
    }

    /**
     * Renders a text that no key holds, such as a game document's, in MiniMessage with its values.
     *
     * @param kinds each value's declared kind, which a missing value shows the replacement word of
     * @param source names the text in the log when a value is missing
     * @throws eu.nordtal.season.messages.text.MessageSyntaxException when the text cannot be read
     */
    public Component format(
            final Viewer viewer,
            final String text,
            final Map<String, ?> values,
            final Map<String, Kind> kinds,
            final String source) {
        return render(
                viewer,
                messages.prepare(viewer, text, values, kinds, TextFormat.MINIMESSAGE, source),
                values,
                true,
                messages.environment().palette());
    }

    private Component format(final Viewer viewer, final MessageRef message, final boolean carded) {
        return render(
                viewer,
                messages.prepare(viewer, message),
                message.args(),
                carded,
                messages.environment().palette());
    }

    private Component render(
            final Viewer viewer,
            final Messages.Prepared prepared,
            final Map<String, ?> args,
            final boolean carded,
            final Palette palette) {
        if (!prepared.markup()) {
            return Component.text(
                    PlainText.of(prepared.pieces(), prepared.language(), prepared.zone(), prepared.words()));
        }
        final Writing writing = new Writing(
                prepared,
                args,
                names,
                carded ? name -> card(name, prepared) : name -> null,
                inner -> render(viewer, messages.prepare(viewer, inner), inner.args(), carded, palette));
        writing.write(prepared.pieces());
        return MiniMessage.miniMessage()
                .deserialize(
                        writing.markup.toString(),
                        TagResolver.resolver(writing.values(), new Tones(palette), GLYPH_TAG));
    }

    private @Nullable Component card(final DisplayName name, final Messages.Prepared prepared) {
        final @Nullable MessageRef card = cards.card(name);
        return card == null ? null : format(new Viewer(prepared.language(), prepared.zone(), null), card, false);
    }

    /** Escapes {@code <} and the backslash.
     * The backslash goes first, otherwise {@code \<tag>} opens a tag.
     */
    static String escape(final String value) {
        return value.indexOf('<') < 0 && value.indexOf('\\') < 0
                ? value
                : value.replace("\\", "\\\\").replace("<", "\\<");
    }

    private static @Nullable Component glyph(final String name) {
        for (final GlyphNames source : GLYPHS) {
            final Component glyph = source.glyph(name);
            if (glyph != null) {
                return glyph;
            }
        }
        return null;
    }

    private static <T> List<T> load(final Class<T> type) {
        return ServiceLoader.load(type, type.getClassLoader()).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
    }

    private static final class Writing {

        private final Messages.Prepared prepared;
        private final Map<String, ?> args;
        private final Names names;
        private final Function<DisplayName, @Nullable Component> cards;
        private final Function<MessageRef, Component> inner;
        private final StringBuilder markup = new StringBuilder();
        private final List<Component> inserted = new ArrayList<>();

        Writing(
                final Messages.Prepared prepared,
                final Map<String, ?> args,
                final Names names,
                final Function<DisplayName, @Nullable Component> cards,
                final Function<MessageRef, Component> inner) {
            this.prepared = prepared;
            this.args = args;
            this.names = names;
            this.cards = cards;
            this.inner = inner;
        }

        void write(final List<Piece> pieces) {
            for (final Piece piece : pieces) {
                switch (piece) {
                    case Piece.Text text -> markup.append(escape(text.text()));
                    case Piece.Filled filled -> insert(component(filled));
                    case Piece.Markup tag -> tag(tag);
                }
            }
        }

        private void insert(final Component component) {
            markup.append('<')
                    .append(VALUE_TAG)
                    .append(':')
                    .append(inserted.size())
                    .append('>');
            inserted.add(component);
        }

        private void tag(final Piece.Markup piece) {
            final Node.Tag tag = piece.tag();
            if ("action".equals(tag.name())) {
                action(piece);
                return;
            }
            markup.append('<');
            if (tag.shape() == Node.Tag.Shape.CLOSE) {
                markup.append('/');
            }
            markup.append(tag.name());
            for (int index = 0; index < piece.args().size(); index++) {
                final char quote = tag.args().get(index).quote();
                markup.append(':');
                if (quote != 0) {
                    markup.append(quote);
                }
                for (final Piece part : piece.args().get(index)) {
                    switch (part) {
                        case Piece.Text text -> markup.append(text.text());
                        case Piece.Filled filled
                        when "hover".equals(tag.name()) ->
                            // The hover text is parsed again, so a value stays a component.
                            insert(component(filled));
                        case Piece.Filled filled -> markup.append(quoted(plain(filled), quote));
                        case Piece.Markup ignored -> {}
                    }
                }
                if (quote != 0) {
                    markup.append(quote);
                }
            }
            if (tag.shape() == Node.Tag.Shape.SELF_CLOSING) {
                markup.append('/');
            }
            markup.append('>');
        }

        private void action(final Piece.Markup piece) {
            if (piece.tag().shape() == Node.Tag.Shape.CLOSE) {
                markup.append("</click>");
                return;
            }
            final String name = piece.tag().args().isEmpty()
                    ? null
                    : piece.tag().args().getFirst().literal();
            final Object bound = name == null ? null : args.get(name);
            final String command = bound instanceof final Action action ? action.command() : "";
            markup.append("<click:run_command:'").append(quoted(command, '\'')).append("'>");
        }

        private String plain(final Piece.Filled filled) {
            return ValueText.of(
                    filled.kind(),
                    filled.value(),
                    filled.style(),
                    prepared.language(),
                    prepared.zone(),
                    prepared.words());
        }

        private Component component(final Piece.Filled filled) {
            final Object value = filled.value();
            if (value == null) {
                return Component.text(prepared.words().missing(filled.kind()));
            }
            return switch (filled.kind()) {
                case DISPLAY_NAME -> name((DisplayName) value, "plain".equals(filled.style()));
                case ITEM -> content((GameContent) value);
                case LIST -> list((List<?>) value, "or".equals(filled.style()));
                case MESSAGE -> inner.apply((MessageRef) value);
                case GLYPH -> {
                    final Component glyph = glyph(((Glyph) value).name());
                    yield glyph == null ? Component.empty() : glyph;
                }
                default -> Component.text(plain(filled));
            };
        }

        private Component content(final GameContent content) {
            final List<Component> filled = new ArrayList<>(content.args().size());
            for (final Object arg : content.args()) {
                filled.add(item(arg));
            }
            return Component.translatable(content.key(), content.english(), filled);
        }

        private Component list(final List<?> items, final boolean or) {
            if (items.isEmpty()) {
                return Component.text(prepared.words().missing(Kind.LIST));
            }
            final List<Component> shown = new ArrayList<>(items.size());
            for (final Object item : items) {
                shown.add(item(item));
            }
            if (shown.size() == 1) {
                return shown.getFirst();
            }
            final Component rest = Component.join(
                    JoinConfiguration.separator(Component.text(", ")), shown.subList(0, shown.size() - 1));
            // The joining text is plain, so markers stand in for the two parts.
            final String joint = prepared.words()
                    .word(
                            or ? "list.or" : "list.and",
                            Map.of("rest", String.valueOf(REST_MARK), "last", String.valueOf(LAST_MARK)));
            final List<Component> parts = new ArrayList<>();
            final StringBuilder text = new StringBuilder();
            for (int index = 0; index < joint.length(); index++) {
                final char c = joint.charAt(index);
                if (c != REST_MARK && c != LAST_MARK) {
                    text.append(c);
                    continue;
                }
                if (!text.isEmpty()) {
                    parts.add(Component.text(text.toString()));
                    text.setLength(0);
                }
                parts.add(c == REST_MARK ? rest : shown.getLast());
            }
            if (!text.isEmpty()) {
                parts.add(Component.text(text.toString()));
            }
            return Component.textOfChildren(parts.toArray(Component[]::new));
        }

        private Component item(final Object value) {
            return switch (value) {
                case final GameContent inner -> content(inner);
                case final DisplayName name -> name(name, false);
                case final MessageRef message -> inner.apply(message);
                default -> {
                    final Kind kind = Kind.ofValue(value).orElse(Kind.TEXT);
                    yield Component.text(ValueText.of(
                            kind == Kind.LIST ? Kind.TEXT : kind,
                            kind == Kind.LIST ? String.valueOf(value) : value,
                            null,
                            prepared.language(),
                            prepared.zone(),
                            prepared.words()));
                }
            };
        }

        private Component name(final DisplayName name, final boolean plain) {
            if (plain) {
                return Component.text(name.name());
            }
            final Component drawn = names.draw(name, prepared.language());
            final @Nullable Component card = cards.apply(name);
            return card == null ? drawn : drawn.hoverEvent(HoverEvent.showText(card));
        }

        TagResolver values() {
            final List<Component> components = List.copyOf(inserted);
            return TagResolver.resolver(VALUE_TAG, (arguments, context) -> {
                final int index = Integer.parseInt(
                        arguments.popOr("a value needs its index").value());
                return Tag.selfClosingInserting(components.get(index));
            });
        }

        private static String quoted(final String value, final char quote) {
            // The validator refuses a value in an argument without quotes.
            return value.replace("\\", "\\\\").replace(String.valueOf(quote), "\\" + quote);
        }
    }

    private record Tones(Palette palette) implements TagResolver {

        @Override
        public @Nullable Tag resolve(final String name, final ArgumentQueue arguments, final Context context)
                throws ParsingException {
            final Tone tone = tone(name);
            if (tone == null) {
                return null;
            }
            final TextColor colour = TextColor.fromHexString(palette.hex(tone));
            return Tag.styling(colour == null ? Objects.requireNonNull(TextColor.fromHexString(tone.hex())) : colour);
        }

        @Override
        public boolean has(final String name) {
            return tone(name) != null;
        }

        private static @Nullable Tone tone(final String name) {
            if (!Tags.TONES.contains(name)) {
                return null;
            }
            return Tone.valueOf(name.toUpperCase(Locale.ROOT));
        }
    }
}
