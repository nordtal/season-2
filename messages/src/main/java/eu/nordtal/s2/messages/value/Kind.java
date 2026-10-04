package eu.nordtal.s2.messages.value;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.messages.MessageRef;
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
import org.jspecify.annotations.Nullable;

/**
 * The closed set of what a message can show, each rendered once per target.
 * A text names a kind by its token, as in {@code {left, duration, short}}; a value's kind follows from its Java type.
 */
public enum Kind {

    /** Words as written, never markup. */
    TEXT("text", "Nordtal"),

    /** A number, grouped as the reader's language groups it unless the style is {@code plain}. */
    NUMBER("number", "3"),

    /** A length of time: {@code long} by default, {@code short} as {@code 2h 5m}, {@code clock} as {@code 2:05:00}. */
    DURATION("duration", "PT2H5M"),

    /**
     * A moment, in the reader's time zone: {@code datetime} by default, or {@code date}, {@code time}.
     * {@code relative} is Discord's countdown, which every other target shows as {@code datetime}.
     */
    INSTANT("instant", "2026-10-03T18:40:00Z"),

    /** An amount of money in the reader's language. */
    MONEY("money", "300"),

    /** Several values joined with {@code and}, or with {@code or} under that style. */
    LIST("list", "Alex,Sam,Kim"),

    /** A player's name, with their hover card in Minecraft unless the style is {@code plain}. */
    DISPLAY_NAME("name", "Alex"),

    /** A Discord member. */
    MENTION("mention", "Alex"),

    /** An item, advancement or entity of the game. */
    ITEM("item", "item.minecraft.diamond"),

    /** A glyph of the resource pack. */
    GLYPH("glyph", "admin"),

    /** Yes or no, or one of a fixed set of states; what {@code select} chooses on. */
    CHOICE("choice", "true"),

    /**
     * Another message, rendered for the same reader in the same target, so a phrase is written once and placed in many.
     * An editor writes it as the key of a message without values.
     */
    MESSAGE("message", "values.missing.text");

    private static final PlayerId EXAMPLE_PLAYER = PlayerId.of(new UUID(0L, 1L));

    /** The kinds whose values are of one type each. */
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
    private final String example;

    Kind(final String token, final String example) {
        this.token = token;
        this.example = example;
    }

    /** Returns how a text names the kind, such as {@code duration}. */
    public String token() {
        return token;
    }

    /** Returns the styles a text may ask for; every kind also renders without one. */
    public Set<String> styles() {
        return switch (this) {
            case NUMBER, DISPLAY_NAME -> Set.of("plain");
            case DURATION -> Set.of("long", "short", "clock", "minutes");
            case INSTANT -> Set.of("datetime", "date", "time", "relative");
            case LIST -> Set.of("and", "or");
            default -> Set.of();
        };
    }

    /** Returns the example an attribute without its own shows, as an editor writes it. */
    public String defaultExample() {
        return example;
    }

    /** Returns the kind a text names by {@code token}, or empty for a word that is none. */
    public static Optional<Kind> byToken(final String token) {
        return Arrays.stream(values()).filter(kind -> kind.token.equals(token)).findFirst();
    }

    /** Returns the kind of a value of {@code type}, or empty for a type that is no kind's. */
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

    /** Returns the kind of a value, or empty for one that has none. */
    public static Optional<Kind> ofValue(final @Nullable Object value) {
        return value == null ? Optional.empty() : of(value.getClass());
    }

    /**
     * Returns a value of this kind from an example as an editor writes it, so a text can be rendered with it.
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
            case MESSAGE -> MessageRef.of(text.strip());
        };
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
