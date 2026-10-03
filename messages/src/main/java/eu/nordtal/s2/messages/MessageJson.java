package eu.nordtal.s2.messages;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.messages.context.Contexts;
import eu.nordtal.s2.messages.context.MessageEnvironment;
import eu.nordtal.s2.messages.text.NodeJson;
import eu.nordtal.s2.messages.value.DisplayName;
import eu.nordtal.s2.messages.value.GameContent;
import eu.nordtal.s2.messages.value.Glyph;
import eu.nordtal.s2.messages.value.Kind;
import eu.nordtal.s2.messages.value.Mention;
import eu.nordtal.s2.messages.value.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A message as data: its key and each value with its kind, so a row or a browser renders it later, in its own target.
 * A value is {@code {"kind": "instant", "value": "2026-10-03T18:40:00Z"}}, a duration in seconds; a context is stored
 * as its attributes. Plain maps and lists, which the one codec writes and reads; the browser reads the same shape.
 */
public final class MessageJson {

    private MessageJson() {}

    /**
     * Returns every key's texts in a language as the web target reads them: key to its variants, each the parsed tree.
     * Overrides are layered and English fills a key the language lacks, as {@link Messages#texts} answers.
     */
    public static Map<String, List<List<Object>>> texts(final Messages messages, final Locale language) {
        final Map<String, List<List<Object>>> json = new TreeMap<>();
        messages.texts(language)
                .forEach((key, variants) -> json.put(
                        key,
                        variants.stream()
                                .map(variant -> NodeJson.of(variant.nodes()))
                                .toList()));
        return json;
    }

    /** Returns {@code {"key": …, "args": {name: {kind, value}}}}, a context's attributes under their dotted names. */
    public static Map<String, Object> encode(final MessageRef message) {
        final Map<String, Object> json = new LinkedHashMap<>();
        json.put("key", message.key());
        json.put("args", encodeArgs(message.args()));
        return json;
    }

    /** Returns each value with its kind; a value of no kind is left out, which renders as the replacement word. */
    public static Map<String, Object> encodeArgs(final Map<String, ?> args) {
        final Map<String, Object> json = new LinkedHashMap<>();
        Contexts.flatten(args, MessageEnvironment.NONE, null).forEach((name, value) -> {
            if (!name.endsWith("." + Contexts.SELF)) {
                final Map<String, Object> typed = typed(value);
                if (typed != null) {
                    json.put(name, typed);
                }
            }
        });
        return json;
    }

    /** Reads what {@link #encode} wrote, as the one codec reads it back into maps. */
    public static MessageRef decode(final Map<?, ?> json) {
        return new MessageRef(
                String.valueOf(json.get("key")),
                json.get("args") instanceof final Map<?, ?> args ? decodeArgs(args) : Map.of());
    }

    /** Reads what {@link #encodeArgs} wrote; a value it cannot read is left out, so it shows the replacement word. */
    public static Map<String, Object> decodeArgs(final Map<?, ?> json) {
        final Map<String, Object> args = new LinkedHashMap<>();
        json.forEach((name, typed) -> {
            final Object value = typed instanceof final Map<?, ?> map ? value(map) : null;
            if (value != null) {
                args.put(String.valueOf(name), value);
            }
        });
        return args;
    }

    private static @Nullable Map<String, Object> typed(final Object value) {
        final Kind kind = Kind.ofValue(value).orElse(null);
        if (kind == null) {
            return null;
        }
        final Map<String, Object> typed = new LinkedHashMap<>();
        typed.put("kind", kind.token());
        typed.put("value", plain(kind, value));
        return typed;
    }

    private static Object plain(final Kind kind, final Object value) {
        return switch (kind) {
            case TEXT, INSTANT -> value.toString();
            case NUMBER -> value;
            case DURATION -> ((Duration) value).toSeconds();
            case MONEY -> {
                final Money money = (Money) value;
                yield Map.of(
                        "minor", money.minor(), "currency", money.currency().getCurrencyCode());
            }
            case LIST -> {
                final List<Object> items = new ArrayList<>();
                for (final Object item : (List<?>) value) {
                    final Map<String, Object> typed = item == null ? null : typed(item);
                    if (typed != null) {
                        items.add(typed);
                    }
                }
                yield items;
            }
            case DISPLAY_NAME -> {
                final DisplayName name = (DisplayName) value;
                yield Map.of("player", name.player().value().toString(), "name", name.name());
            }
            case MENTION -> {
                final Mention mention = (Mention) value;
                yield Map.of("member", mention.member().value(), "name", mention.name());
            }
            case ITEM -> {
                final GameContent content = (GameContent) value;
                final Map<String, Object> json = new LinkedHashMap<>();
                json.put("key", content.key());
                json.put("english", content.english());
                if (!content.args().isEmpty()) {
                    json.put("args", plain(Kind.LIST, content.args()));
                }
                yield json;
            }
            case GLYPH -> ((Glyph) value).name();
            case CHOICE -> value instanceof Boolean ? value : Kind.choiceOf(value);
        };
    }

    /** A choice comes back as its word, which a {@code select} chooses on and a text shows, as the enum did. */
    private static @Nullable Object value(final Map<?, ?> typed) {
        final Object value = typed.get("value");
        final Kind kind = Kind.byToken(String.valueOf(typed.get("kind"))).orElse(null);
        if (kind == null || value == null) {
            return null;
        }
        try {
            return switch (kind) {
                case TEXT -> (String) value;
                case NUMBER -> number(value.toString());
                case DURATION -> Duration.ofSeconds(((Number) value).longValue());
                case INSTANT -> Instant.parse((String) value);
                case MONEY -> {
                    final Map<?, ?> money = (Map<?, ?>) value;
                    yield new Money(
                            field(money, "minor", Number.class).longValue(),
                            Currency.getInstance(field(money, "currency", String.class)));
                }
                case LIST -> items((List<?>) value);
                case DISPLAY_NAME -> {
                    final Map<?, ?> name = (Map<?, ?>) value;
                    yield new DisplayName(
                            PlayerId.of(UUID.fromString(field(name, "player", String.class))),
                            field(name, "name", String.class));
                }
                case MENTION -> {
                    final Map<?, ?> mention = (Map<?, ?>) value;
                    yield new Mention(
                            DiscordId.of(field(mention, "member", String.class)), field(mention, "name", String.class));
                }
                case ITEM -> {
                    final Map<?, ?> content = (Map<?, ?>) value;
                    yield new GameContent(
                            field(content, "key", String.class),
                            field(content, "english", String.class),
                            content.get("args") instanceof final List<?> args ? items(args) : List.of());
                }
                case GLYPH -> new Glyph((String) value);
                case CHOICE -> value instanceof Boolean ? value : (String) value;
            };
        } catch (final RuntimeException unreadable) {
            return null;
        }
    }

    /** A whole number stays whole, as JSON does not tell 1 from 1.0 and the codec reads every number as a double. */
    private static BigDecimal number(final String json) {
        final BigDecimal read = new BigDecimal(json);
        return read.scale() > 0 && read.stripTrailingZeros().scale() <= 0 ? read.setScale(0) : read;
    }

    /** A field a value must have; one missing throws, which leaves the value out. */
    private static <T> T field(final Map<?, ?> json, final String name, final Class<T> type) {
        return type.cast(Objects.requireNonNull(json.get(name), name));
    }

    private static List<Object> items(final List<?> json) {
        final List<Object> items = new ArrayList<>();
        for (final Object item : json) {
            final Object read = item instanceof final Map<?, ?> typed ? value(typed) : null;
            if (read != null) {
                items.add(read);
            }
        }
        return items;
    }
}
