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
 * Renders a {@link Messages} bundle's values as MiniMessage, escaping every placeholder value.
 *
 * Separate from {@code Messages} because {@code discord-bot} has no Adventure at runtime.
 */
public final class MessageRenderer {

    private final Messages messages;

    public MessageRenderer(final Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /**
     * Returns a renderer over {@code messages}.
     * It allocates rather than caching, since MiniMessage's parser is already a cached singleton.
     */
    public static MessageRenderer of(final Messages messages) {
        return new MessageRenderer(messages);
    }

    /** Returns the raw bundle behind this renderer. */
    public Messages raw() {
        return messages;
    }

    /** Returns the message at {@code key}, parsed as MiniMessage. */
    public Component get(final Locale locale, final String key) {
        return parse(messages.get(locale, key));
    }

    /**
     * Formats a bundle message as MiniMessage, with every placeholder value escaped.
     * A {@link Map} as {@code parameters} is refused at runtime.
     *
     * @param parameters alternating name and value, as {@link Messages#format(Locale, String, Object...)} takes them
     */
    public Component format(final Locale locale, final String key, final Object... parameters) {
        return format(locale, key, Map.of(), parameters);
    }

    /**
     * Formats the same, plus values that are already {@link Component}s and fill MiniMessage tags unescaped.
     * For a death message, an advancement title or a player's composition, which cannot survive a {@code String}.
     *
     * @param components tag name to component, e.g. {@code Map.of("death", event.deathMessage())}
     * @param parameters the ordinary alternating name and value pairs, escaped as always
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
