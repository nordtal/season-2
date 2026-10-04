package eu.nordtal.s2.messagerendering;

import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.Palette;
import eu.nordtal.s2.messages.Tone;
import eu.nordtal.s2.messages.Viewer;
import eu.nordtal.s2.messages.text.Node;
import eu.nordtal.s2.messages.text.Piece;
import eu.nordtal.s2.messages.text.PlainText;
import eu.nordtal.s2.messages.text.Tags;
import eu.nordtal.s2.messages.value.Action;
import eu.nordtal.s2.messages.value.DisplayName;
import eu.nordtal.s2.messages.value.GameContent;
import eu.nordtal.s2.messages.value.Glyph;
import eu.nordtal.s2.messages.value.Kind;
import eu.nordtal.s2.messages.value.ValueText;
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

/**
 * The Minecraft target: renders a message as an Adventure component, apart from {@code Messages} for the bot's sake.
 * The text's own markup is MiniMessage; every value is a component a tag inserts, never characters spliced into the
 * markup, so nothing a player typed can open a tag.
 */
public final class MessageRenderer {

    /** The tag a value is inserted by; its one argument is the value's index. */
    private static final String VALUE_TAG = "nordtal-value";

    /** Where a list's leading items and its last stand in the joint, characters no bundle text holds. */
    private static final char REST_MARK = '\u0001';

    private static final char LAST_MARK = '\u0002';

    private static final List<GlyphNames> GLYPHS = load(GlyphNames.class);

    /** {@code <glyph:name>}, drawn by the {@link GlyphNames} this process ships; an unknown name is refused. */
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

    /**
     * Returns a renderer over {@code messages} that shows a player's bare name.
     * It allocates rather than caching, since MiniMessage's parser is already a cached singleton.
     */
    public static MessageRenderer of(final Messages messages) {
        return new MessageRenderer(messages);
    }

    /** Returns a renderer over {@code messages} that draws every player's name with {@code names}. */
    public static MessageRenderer of(final Messages messages, final Names names) {
        return new MessageRenderer(messages, names);
    }

    /** Returns a renderer that draws every player's name with {@code names} and shows its card on hover. */
    public static MessageRenderer of(final Messages messages, final Names names, final NameCards cards) {
        return new MessageRenderer(messages, names, cards);
    }

    /** Returns the same bundle with every name bare and no card, for a reader that draws neither, such as a console. */
    public MessageRenderer bare() {
        return new MessageRenderer(messages);
    }

    /** Returns the raw bundle behind this renderer. */
    public Messages raw() {
        return messages;
    }

    /** Renders a message for a reader known only by language, in the network's zone. */
    public Component format(final @Nullable Locale locale, final MessageRef message) {
        return format(Viewer.of(locale), message);
    }

    /** Renders a message for a reader: their language, their zone, and {@code self} for the roles that are them. */
    public Component format(final Viewer viewer, final MessageRef message) {
        return format(viewer, message, true);
    }

    /**
     * Renders a text an admin is trying for a message's key, in place of the key's own, with the message's values.
     *
     * @throws eu.nordtal.s2.messages.text.MessageSyntaxException when the text cannot be read
     */
    public Component format(final Viewer viewer, final MessageRef message, final String text) {
        return render(messages.prepare(viewer, message, text), message, true);
    }

    /** Renders a message, its names with their cards unless it is a card itself, which never draws a card. */
    private Component format(final Viewer viewer, final MessageRef message, final boolean carded) {
        return render(messages.prepare(viewer, message), message, carded);
    }

    private Component render(final Messages.Prepared prepared, final MessageRef message, final boolean carded) {
        if (!prepared.markup()) {
            return Component.text(
                    PlainText.of(prepared.pieces(), prepared.language(), prepared.zone(), prepared.words()));
        }
        final Writing writing =
                new Writing(prepared, message.args(), names, carded ? name -> card(name, prepared) : name -> null);
        writing.write(prepared.pieces());
        return MiniMessage.miniMessage()
                .deserialize(
                        writing.markup.toString(),
                        TagResolver.resolver(
                                writing.values(),
                                new Tones(messages.environment().palette()),
                                GLYPH_TAG));
    }

    /** Returns the card of {@code name} for the reader {@code prepared} is for, or {@code null} where there is none. */
    private @Nullable Component card(final DisplayName name, final Messages.Prepared prepared) {
        final @Nullable MessageRef card = cards.card(name);
        return card == null ? null : format(new Viewer(prepared.language(), prepared.zone(), null), card, false);
    }

    /**
     * Makes characters inert for MiniMessage.
     *
     * The backslash is escaped before {@code <}; the other order lets {@code \<tag>} open a tag.
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

    /** One message being written as MiniMessage, with the components its values became. */
    private static final class Writing {

        private final Messages.Prepared prepared;
        private final Map<String, ?> args;
        private final Names names;
        private final Function<DisplayName, @Nullable Component> cards;
        private final StringBuilder markup = new StringBuilder();
        private final List<Component> inserted = new ArrayList<>();

        Writing(
                final Messages.Prepared prepared,
                final Map<String, ?> args,
                final Names names,
                final Function<DisplayName, @Nullable Component> cards) {
            this.prepared = prepared;
            this.args = args;
            this.names = names;
            this.cards = cards;
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
                        // A quoted argument's text is the text's own markup, escapes included.
                        case Piece.Text text -> markup.append(text.text());
                        case Piece.Filled filled
                        when "hover".equals(tag.name()) ->
                            // The hover text is parsed again, with these resolvers: a value stays a component.
                            insert(component(filled));
                        case Piece.Filled filled -> markup.append(quoted(plain(filled), quote));
                        case Piece.Markup ignored -> {
                            // The parser puts no tag inside an argument.
                        }
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

        /** {@code <action:name>} becomes a click that runs the command the code bound to that name. */
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
            // An unbound action still opens a click, so its closing tag closes something.
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
                case GLYPH -> {
                    final Component glyph = glyph(((Glyph) value).name());
                    yield glyph == null ? Component.empty() : glyph;
                }
                default -> Component.text(plain(filled));
            };
        }

        /** The game's own line, translated by the client, each argument a component of its own kind. */
        private Component content(final GameContent content) {
            final List<Component> filled = new ArrayList<>(content.args().size());
            for (final Object arg : content.args()) {
                filled.add(item(arg));
            }
            return Component.translatable(content.key(), content.english(), filled);
        }

        /** A list joined with the reader's words, each item a component of its own kind, so a game name stays one. */
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
            // The joint is the bundle's plain text, so the two parts stand in it as markers and are put back here.
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

        /** One value inside another, a game line's argument or a list's item, as a component of its kind. */
        private Component item(final Object value) {
            return switch (value) {
                case final GameContent inner -> content(inner);
                case final DisplayName name -> name(name, false);
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

        /** The name as this process draws it, with its card on hover; {@code plain} is the bare name alone. */
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

        /** Escapes a spliced value for the argument it stands in, so it cannot end that argument early. */
        private static String quoted(final String value, final char quote) {
            // The validator refuses a value in an argument without quotes.
            return value.replace("\\", "\\\\").replace(String.valueOf(quote), "\\" + quote);
        }
    }

    /** The tone tags, painted from the palette as it is at this render. */
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
