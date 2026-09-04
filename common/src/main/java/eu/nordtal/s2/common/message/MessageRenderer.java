package eu.nordtal.s2.common.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

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
        if (parameters.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "parameters must alternate name and value, got " + parameters.length);
        }
        final Map<String, Object> escaped = new LinkedHashMap<>();
        for (int i = 0; i < parameters.length; i += 2) {
            escaped.put(String.valueOf(parameters[i]), escape(String.valueOf(parameters[i + 1])));
        }
        return MiniMessage.miniMessage().deserialize(messages.format(locale, key, escaped));
    }

    /**
     * Makes a substituted value inert for MiniMessage. Only {@code <} can begin a tag, and
     * MiniMessage's own escape for it is a backslash - so one character has to be handled, and it is
     * handled here rather than trusted never to appear.
     */
    static String escape(final String value) {
        return value.indexOf('<') < 0 ? value : value.replace("<", "\\<");
    }
}
