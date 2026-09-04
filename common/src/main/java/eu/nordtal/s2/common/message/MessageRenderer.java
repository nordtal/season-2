package eu.nordtal.s2.common.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Renders a {@link Messages} bundle's values as <b>MiniMessage</b>.
 *
 * <h2>Why this is not a method on {@code Messages}</h2>
 * {@code Messages} lives in {@code :common} and is used by {@code discord-bot}, which has no
 * Adventure on its runtime classpath at all - Adventure is {@code compileOnly} here because Paper
 * and Velocity provide it and the bot has neither platform. A {@code Component}-returning method on
 * {@code Messages} would therefore be a class the bot cannot link. Keeping the rendering in its own
 * class means the bot never loads it, and the plugins get one place that knows the format.
 *
 * <h2>Placeholder values are escaped, and that is not optional</h2>
 * {@code Messages.format} substitutes into the raw string, so substitution happens <em>before</em>
 * MiniMessage sees anything. A value is arbitrary text - a player name, a world name, a milestone
 * title out of a YAML file somebody edits - and only {@code <} can begin a tag, so exactly one
 * character has to be made inert. {@code network-control}'s {@code Placeholders} solved this for
 * the MOTD in 2026-09-01 and {@code PlaceholdersTest} pins it; this is the same rule, applied to
 * every message rather than to one.
 *
 * <h2>A bundle that carries no tags renders as its own plain text</h2>
 * Which is what makes this safe to put in front of the existing bundles: 510 lines of message text
 * were written before anything parsed them, and MiniMessage returns literal text for a string with
 * no tags in it. The two exceptions in this repository are known and both want the treatment -
 * {@code network-control}'s {@code motd.misconfigured} already writes {@code <red>} and was already
 * being deserialized by hand, and {@code hunger-games}' bundles carry a legacy section code that
 * MiniMessage does not read. Section codes are the one thing this class cannot rescue; they have to
 * be rewritten as tags.
 */
public final class MessageRenderer {

    private final Messages messages;

    public MessageRenderer(final Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /**
     * A renderer over {@code messages}, for a call site that has a bundle and wants a component.
     *
     * <p><b>It allocates rather than caching, and that is deliberate.</b> The object holds one
     * reference and nothing else; the work is in MiniMessage's parser, which is a cached singleton.
     * A cache keyed on {@code Messages} identity would buy nothing measurable and would be one more
     * thing that has to be right. What this exists for is the call sites - some ninety of them -
     * that hold a {@code Messages} and would otherwise each need a second field threaded through a
     * constructor to say the same thing.</p>
     *
     * <p>The one place not to use it is a loop that runs every tick. Nothing in this repository
     * does: the two boss bar HUDs compose {@code String}s and wrap them once.</p>
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
        return MiniMessage.miniMessage().deserialize(messages.get(locale, key));
    }

    /**
     * @param parameters alternating name and value, as {@link Messages#format(Locale, String,
     *                   Object...)} takes them; every value is made inert for MiniMessage first
     * @return the formatted message, parsed as MiniMessage
     */
    public Component format(final Locale locale, final String key, final Object... parameters) {
        return format(locale, key, Map.of(), parameters);
    }

    /**
     * The same, plus values that are already {@link Component}s.
     *
     * <h2>Why there are two kinds of value at all</h2>
     * A {@code {name}} placeholder is substituted into the raw string before MiniMessage sees it,
     * which is exactly what makes escaping possible - and exactly what makes it useless for a value
     * that is already styled. Three things in this network are components before they are anything
     * else and cannot survive a trip through {@code String}:
     *
     * <ul>
     *   <li><b>Vanilla's death message.</b> It is a {@code TranslatableComponent}, so every reader's
     *       own client renders it in their own language, with the mob's name and the killer's
     *       weapon in it. Nothing in a bundle here can do that, and flattening it to text would
     *       throw the per-viewer translation away.</li>
     *   <li><b>An advancement's title</b>, for the same reason.</li>
     *   <li><b>A player's composition</b> - flag, name, crest - which is glyphs in a specific font
     *       and specific colours.</li>
     * </ul>
     *
     * <p>These arrive as MiniMessage <em>tags</em> ({@code <sender>}) rather than as braces, so the
     * bundle still decides where they sit and what is around them, and the two kinds cannot be
     * confused by whoever edits the file. A component value is not escaped and does not need to be:
     * it never passes through the parser at all.</p>
     *
     * @param components tag name to component, e.g. {@code Map.of("death", event.deathMessage())}
     *                   for a bundle value containing {@code <death>}
     * @param parameters the ordinary alternating name/value pairs, escaped as always
     * @return the formatted message, parsed as MiniMessage
     */
    public Component format(final Locale locale, final String key,
                            final Map<String, Component> components, final Object... parameters) {
        if (parameters.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "parameters must alternate name and value, got " + parameters.length);
        }
        final Map<String, Object> escaped = new LinkedHashMap<>();
        for (int i = 0; i < parameters.length; i += 2) {
            escaped.put(String.valueOf(parameters[i]), escape(String.valueOf(parameters[i + 1])));
        }
        final String raw = messages.format(locale, key, escaped);
        if (components.isEmpty()) {
            return MiniMessage.miniMessage().deserialize(raw);
        }
        final TagResolver.Builder resolver = TagResolver.builder();
        components.forEach((name, value) -> resolver.resolver(Placeholder.component(name, value)));
        return MiniMessage.miniMessage().deserialize(raw, resolver.build());
    }

    /**
     * Makes a substituted value inert for MiniMessage. Only {@code <} can begin a tag, and
     * MiniMessage's own escape for it is a backslash - so one character has to be handled, and it is
     * handled here rather than trusted never to appear.
     *
     * <p>Public because {@code network-control}'s MOTD substitutes its own placeholders - dynamic
     * names such as {@code {players:smp}} that no name/value pair can express - and then needs
     * exactly this rule. It had its own copy of these two lines until 2026-09-04, which is two
     * implementations of one security property and no test comparing them.</p>
     *
     * @param value an arbitrary substituted value - a player name, a world name, a milestone title
     *              out of a YAML file somebody edits
     * @return the same text, unable to open a tag
     */
    public static String escape(final String value) {
        return value.indexOf('<') < 0 ? value : value.replace("<", "\\<");
    }
}
