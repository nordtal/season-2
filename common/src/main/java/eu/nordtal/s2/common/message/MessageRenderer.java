package eu.nordtal.s2.common.message;

import eu.nordtal.s2.common.message.context.Contexts;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Renders a {@link Messages} bundle's values as MiniMessage.
 *
 * Separate from {@code Messages} because {@code discord-bot} has no Adventure at runtime. Placeholder
 * values are escaped before parsing, since they are arbitrary text such as player names. A bundle
 * without tags renders as its own text; legacy section codes are not supported.
 */
public final class MessageRenderer {

    private final Messages messages;

    public MessageRenderer(final Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /**
     * A renderer over {@code messages}, for a call site that has a bundle and wants a component.
     *
     * <b>It allocates rather than caching, and that is deliberate.</b> The object holds one
     * reference and nothing else; the work is in MiniMessage's parser, which is a cached singleton.
     * A cache keyed on {@code Messages} identity would buy nothing measurable and would be one more
     * thing that has to be right. What this exists for is the call sites - some ninety of them -
     * that hold a {@code Messages} and would otherwise each need a second field threaded through a
     * constructor to say the same thing.
     *
     * The one place not to use it is a loop that runs every tick. Nothing in this repository
     * does: the two boss bar HUDs compose {@code String}s and wrap them once.
     *
     * @param messages the bundle to render
     * @return a renderer over it
     */
    public static MessageRenderer of(final Messages messages) {
        return new MessageRenderer(messages);
    }

    /** The raw bundle behind this renderer, for the callers that genuinely want a {@code String}. */
    public Messages raw() {
        return messages;
    }

    /** @return the message at {@code key}, parsed as MiniMessage */
    public Component get(final Locale locale, final String key) {
        return parse(messages.get(locale, key));
    }

    /**
     * Formats a bundle message as MiniMessage, with every placeholder value escaped.
     *
     * A {@link Map} passed as {@code parameters} compiles and is refused at runtime; flatten it or call
     * {@link Messages#format(Locale, String, Map)}.
     *
     * @param parameters alternating name and value, as {@link Messages#format(Locale, String, Object...)} takes them
     */
    public Component format(final Locale locale, final String key, final Object... parameters) {
        return format(locale, key, Map.of(), parameters);
    }

    /**
     * The same, plus values that are already {@link Component}s.
     *
     * <b>Why there are two kinds of value at all</b>
     *
     * A {@code {name}} placeholder is substituted into the raw string before MiniMessage sees it,
     * which is exactly what makes escaping possible - and exactly what makes it useless for a value
     * that is already styled. Three things in this network are components before they are anything
     * else and cannot survive a trip through {@code String}:
     *
     * <b>Vanilla's death message.</b> It is a {@code TranslatableComponent}, so every reader's own
     * client renders it in their own language, with the mob's name and the killer's weapon in it.
     * Nothing in a bundle here can do that, and flattening it to text would throw the per-viewer
     * translation away. <b>An advancement's title</b>, for the same reason. <b>A player's
     * composition</b> - flag, name, crest - which is glyphs in a specific font and specific colours.
     *
     * These arrive as MiniMessage <em>tags</em> ({@code <sender>}) rather than as braces, so the
     * bundle still decides where they sit and what is around them, and the two kinds cannot be
     * confused by whoever edits the file. A component value is not escaped and does not need to be:
     * it never passes through the parser at all.
     *
     * @param components tag name to component, e.g. {@code Map.of("death", event.deathMessage())}
     *                   for a bundle value containing {@code <death>}
     * @param parameters the ordinary alternating name/value pairs, escaped as always
     * @return the formatted message, parsed as MiniMessage
     */
    public Component format(
            final Locale locale,
            final String key,
            final Map<String, Component> components,
            final Object... parameters) {
        if (parameters.length % 2 != 0) {
            // A whole Map where the pairs belong: "got 1" describes an argument nobody counted.
            if (parameters.length == 1 && parameters[0] instanceof final Map<?, ?> map) {
                throw new IllegalArgumentException(
                        "format takes alternating name and value, and was given a Map of " + map.size()
                                + " entries as a single parameter. A Map is one Object, so this"
                                + " compiles and only fails here - including when the map is empty."
                                + " Flatten it into name, value, name, value (PaperUser, VelocityUser"
                                + " and ConsoleUser each do), or call Messages#format, which does take"
                                + " a Map.");
            }
            throw new IllegalArgumentException("parameters must alternate name and value, got " + parameters.length);
        }
        final Map<String, Object> escaped = new LinkedHashMap<>();
        for (int i = 0; i < parameters.length; i += 2) {
            escaped.put(String.valueOf(parameters[i]), escape(String.valueOf(parameters[i + 1])));
        }
        final String raw = messages.format(locale, key, escaped);
        final TagResolver.Builder resolver = TagResolver.builder().resolver(GlyphTag.RESOLVER);
        components.forEach((name, value) -> resolver.resolver(Placeholder.component(name, value)));
        return MiniMessage.miniMessage().deserialize(raw, resolver.build());
    }

    /**
     * Renders a message a spec chose.
     *
     * A {@link Component} value fills its {@code <name>} tag, every other value its escaped {@code {name}}.
     */
    public Component format(final Locale locale, final MessageRef message) {
        final Map<String, Component> components = new LinkedHashMap<>();
        final Map<String, Object> values = Contexts.flatten(message.args());
        final Object[] parameters = new Object[values.size() * 2];
        int index = 0;
        for (final Map.Entry<String, Object> arg : values.entrySet()) {
            if (arg.getValue() instanceof final Component component) {
                components.put(arg.getKey(), component);
            } else {
                parameters[index++] = arg.getKey();
                parameters[index++] = arg.getValue();
            }
        }
        return format(locale, message.key(), components, java.util.Arrays.copyOf(parameters, index));
    }

    /**
     * Parses a bundle text with every tag a bundle may use, {@code <glyph:name>} included.
     *
     * @param text MiniMessage whose substituted values were already {@link #escape}d
     */
    public static Component parse(final String text) {
        return MiniMessage.miniMessage().deserialize(text, GlyphTag.RESOLVER);
    }

    /**
     * Makes a substituted value inert for MiniMessage.
     *
     * The backslash is escaped before {@code <}; the other order lets {@code \<tag>} open a tag.
     */
    public static String escape(final String value) {
        return value.indexOf('<') < 0 && value.indexOf('\\') < 0
                ? value
                : value.replace("\\", "\\\\").replace("<", "\\<");
    }
}
