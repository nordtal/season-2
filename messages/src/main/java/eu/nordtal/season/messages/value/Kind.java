package eu.nordtal.season.messages.value;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.messages.MessageRef;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** The closed set of what a message can show.
 * A text names a kind by its token, as in {@code {left, duration, short}}.
 * A value's kind follows from its Java type.
 */
public enum Kind {

    /** Words as written, never markup. */
    TEXT("text"),

    /** A number, grouped as the reader's language groups it unless the style is {@code plain}. */
    NUMBER("number"),

    /** A length of time: {@code long} by default, {@code short} as {@code 2h 5m}, {@code clock} as {@code 2:05:00}. */
    DURATION("duration"),

    /** A moment in the reader's time zone: {@code datetime} by default, or {@code date}, {@code time}.
     * {@code relative} is Discord's countdown.
     * Every other target shows it as {@code datetime}.
     */
    INSTANT("instant"),

    /** An amount of money in the reader's language. */
    MONEY("money"),

    /** Several values joined with {@code and}, or with {@code or} under that style. */
    LIST("list"),

    /** A player's name, with their hover card in Minecraft unless the style is {@code plain}. */
    DISPLAY_NAME("name"),

    /** A Discord member. */
    MENTION("mention"),

    /** An item, advancement or entity of the game. */
    ITEM("item"),

    /** A glyph of the resource pack. */
    GLYPH("glyph"),

    /** Yes or no, or one of a fixed set of states. {@code select} chooses on it. */
    CHOICE("choice"),

    /** Another message, rendered for the same reader in the same target.
     * An editor writes it as the key of a message without values, as {@code bundle/key} or as the key alone.
     * An example of a message that needs values, such as a size, is written as its words, which show as written.
     */
    MESSAGE("message");

    private static final Pattern MESSAGE_KEY = Pattern.compile("([a-z][a-z0-9-]*/)?[A-Za-z0-9][A-Za-z0-9_.-]*");

    private static final PlayerId EXAMPLE_PLAYER = PlayerId.of(new UUID(0L, 1L));

    private static final Map<Class<?>, Kind> BY_TYPE = Map.of(
            Duration.class, DURATION,
            Instant.class, INSTANT,
            Money.class, MONEY,
            DisplayName.class, DISPLAY_NAME,
            Mention.class, MENTION,
            GameContent.class, ITEM,
            Glyph.class, GLYPH,
            MessageRef.class, MESSAGE);

    private final String token;

    Kind(final String token) {
        this.token = token;
    }

    /** Returns how a text names the kind, such as {@code duration}. */
    public String token() {
        return token;
    }

    /** Returns the styles a text may ask for. Every kind also renders without one. */
    public Set<String> styles() {
        return switch (this) {
            case NUMBER, DISPLAY_NAME -> Set.of("plain");
            case DURATION -> Set.of("long", "short", "clock", "minutes");
            case INSTANT -> Set.of("datetime", "date", "time", "relative");
            case LIST -> Set.of("and", "or");
            default -> Set.of();
        };
    }

    /** Returns the values a {@code select} tells apart: enum constants, else true and false, else none. */
    public static List<String> choices(final Class<?> type) {
        if (type == boolean.class || type == Boolean.class) {
            return List.of("true", "false");
        }
        final Object[] constants = type.getEnumConstants();
        return constants == null
                ? List.of()
                : Arrays.stream(constants).map(Kind::choiceOf).toList();
    }

    public static Optional<Kind> byToken(final String token) {
        return Arrays.stream(values()).filter(kind -> kind.token.equals(token)).findFirst();
    }

    public static Optional<Kind> of(final Class<?> type) {
        final Class<?> boxed = type.isPrimitive() ? box(type) : type;
        if (CharSequence.class.isAssignableFrom(boxed)) {
            return Optional.of(TEXT);
        }
        if (Number.class.isAssignableFrom(boxed)) {
            return Optional.of(NUMBER);
        }
        if (boxed == Boolean.class || boxed.isEnum()) {
            return Optional.of(CHOICE);
        }
        if (List.class.isAssignableFrom(boxed)) {
            return Optional.of(LIST);
        }
        return Optional.ofNullable(BY_TYPE.get(boxed));
    }

    public static Optional<Kind> ofValue(final @Nullable Object value) {
        return value == null ? Optional.empty() : of(value.getClass());
    }

    /**
     * Returns a value of this kind from an example as an editor writes it, or the words of a message example.
     *
     * @throws IllegalArgumentException when the example does not read as this kind
     */
    public Object example(final String text) {
        return switch (this) {
            case TEXT -> text;
            case NUMBER -> new BigDecimal(text.strip());
            case DURATION -> Duration.parse(text.strip());
            case INSTANT -> Instant.parse(text.strip());
            case MONEY -> Money.euroCents(Long.parseLong(text.strip()));
            case LIST -> List.of(text.split(",", -1));
            case DISPLAY_NAME -> new DisplayName(EXAMPLE_PLAYER, text);
            case MENTION -> new Mention(DiscordId.of("1"), text);
            case ITEM -> GameContent.of(text);
            case GLYPH -> new Glyph(text);
            case CHOICE -> "true".equals(text) || "false".equals(text) ? Boolean.valueOf(text) : text;
            // The bundles load as one and name their keys without the bundle, as a message the code sends does.
            case MESSAGE ->
                namesMessage(text.strip())
                        ? MessageRef.of(text.strip().substring(text.strip().indexOf('/') + 1))
                        : text;
        };
    }

    /** Returns a value of this kind from an example, each item of a list read as a message where {@code messages}. */
    public Object example(final String text, final boolean messages) {
        return this == LIST && messages
                ? Arrays.stream(text.split(",", -1)).map(MESSAGE::example).toList()
                : example(text);
    }

    /** Returns whether {@code text} is written as a message key, {@code key} or {@code bundle/key}; words are not. */
    public static boolean namesMessage(final String text) {
        return MESSAGE_KEY.matcher(text).matches();
    }

    /** Returns how {@code select} names a choice: {@code true}, {@code false}, or an enum constant in kebab case. */
    public static String choiceOf(final Object value) {
        if (value instanceof final Enum<?> constant) {
            return constant.name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
        return String.valueOf(value);
    }

    private static Class<?> box(final Class<?> primitive) {
        if (primitive == int.class) {
            return Integer.class;
        }
        if (primitive == long.class) {
            return Long.class;
        }
        if (primitive == double.class) {
            return Double.class;
        }
        if (primitive == float.class) {
            return Float.class;
        }
        if (primitive == boolean.class) {
            return Boolean.class;
        }
        if (primitive == short.class) {
            return Short.class;
        }
        if (primitive == byte.class) {
            return Byte.class;
        }
        return primitive;
    }
}
