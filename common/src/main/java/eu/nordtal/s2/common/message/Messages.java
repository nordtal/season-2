package eu.nordtal.s2.common.message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The season 2 message system: one map of strings per language, a lookup with named parameters,
 * and English as the fallback for everything.
 * <p>
 * Every user-visible string in season 2 goes through this - bot embeds, DMs, disconnect screens
 * and plugin messages alike. It is deliberately small: {@code java.util.ResourceBundle} was not
 * used because its per-call locale negotiation, its {@code MissingResourceException} and its
 * caching behaviour are all things this needs the opposite of. A missing key must never throw on
 * a login path, and must be reported once rather than once per player per second.
 * </p>
 *
 * <h2>Bundle format</h2>
 * One {@code .properties} file per language on the classpath, read as <b>UTF-8</b>:
 * <pre>
 * messages/access/en.properties
 * messages/access/de.properties
 * </pre>
 * Keys are dotted and lowercase ({@code disconnect.not-linked}). Parameters are named and written
 * in braces:
 * <pre>
 * disconnect.expired=Your access ran out on {date}. Buy more in #{channel}.
 * </pre>
 *
 * <h2>Fallback</h2>
 * Language match, then English, then the key itself. The key is returned rather than an empty
 * string or an exception so that a missing translation shows up on screen as
 * {@code disconnect.expired} - visible, reportable, and harmless.
 */
public final class Messages {

    private static final Logger LOGGER = LoggerFactory.getLogger(Messages.class);

    private final String root;

    /** language tag -> key -> template. Immutable after construction. */
    private final Map<String, Map<String, String>> byLanguage;

    /** Keys already reported missing, so a hot loop logs once and not per call. */
    private final Set<String> reportedMissing = ConcurrentHashMap.newKeySet();

    private Messages(final String root, final Map<String, Map<String, String>> byLanguage) {
        this.root = root;
        this.byLanguage = byLanguage;
    }

    /**
     * Loads a bundle from the classpath of this class's own class loader.
     *
     * @param root    the resource directory, e.g. {@code messages/access}
     * @param locales the languages to load; English is loaded whether or not it is listed,
     *                because it is the fallback
     * @return the loaded bundle
     * @throws IllegalStateException if the English file is missing - a bundle without its fallback
     *                               is a packaging mistake and is worth failing at startup for
     * @throws UncheckedIOException  if a file exists but cannot be read
     */
    public static Messages load(final String root, final Locale... locales) {
        return load(Messages.class.getClassLoader(), root, locales);
    }

    /**
     * Loads a bundle from a specific class loader - a Paper plugin's, for instance.
     *
     * @param classLoader the loader to read the resources from
     * @param root        the resource directory, e.g. {@code messages/access}
     * @param locales     the languages to load; English is always loaded
     * @return the loaded bundle
     * @throws IllegalStateException if the English file is missing
     * @throws UncheckedIOException  if a file exists but cannot be read
     */
    public static Messages load(final ClassLoader classLoader, final String root, final Locale... locales) {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(root, "root");

        final String normalisedRoot = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;

        final Map<String, Map<String, String>> loaded = new LinkedHashMap<>();
        for (final Locale locale : withDefault(locales)) {
            final String language = Locales.tag(locale);
            final Map<String, String> entries = read(classLoader, normalisedRoot, language);
            if (entries == null) {
                if (language.equals(Locales.DEFAULT.getLanguage())) {
                    throw new IllegalStateException(
                            "Message bundle " + normalisedRoot + "/" + language + ".properties is missing; "
                                    + "English is the fallback for every other language and must exist");
                }
                LOGGER.warn("No message bundle {}/{}.properties - {} falls back to English",
                        normalisedRoot, language, language);
                continue;
            }
            loaded.put(language, Map.copyOf(entries));
        }

        return new Messages(normalisedRoot, Map.copyOf(loaded));
    }

    private static List<Locale> withDefault(final Locale... locales) {
        final Map<String, Locale> unique = new LinkedHashMap<>();
        unique.put(Locales.tag(Locales.DEFAULT), Locales.DEFAULT);
        if (locales != null) {
            for (final Locale locale : locales) {
                unique.put(Locales.tag(locale), locale);
            }
        }
        return List.copyOf(unique.values());
    }

    private static Map<String, String> read(final ClassLoader classLoader,
                                            final String root,
                                            final String language) {
        final String resource = root + "/" + language + ".properties";
        try (InputStream stream = classLoader.getResourceAsStream(resource)) {
            if (stream == null) {
                return null;
            }

            // Read as UTF-8 explicitly. Properties.load(InputStream) is ISO-8859-1 and would turn
            // every umlaut in the German bundle into mojibake.
            final Properties properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));

            final Map<String, String> entries = new HashMap<>(properties.size());
            properties.forEach((key, value) -> entries.put(String.valueOf(key), String.valueOf(value)));
            return entries;
        } catch (final IOException exception) {
            throw new UncheckedIOException("Cannot read message bundle " + resource, exception);
        }
    }

    /**
     * Looks a key up, with no parameter substitution.
     *
     * @param locale the language wanted; {@code null} means English
     * @param key    the message key
     * @return the message in that language, in English if it is not translated, or the key itself
     *         if no bundle has it
     */
    public String get(final Locale locale, final String key) {
        Objects.requireNonNull(key, "key");

        final String language = Locales.tag(locale);
        final Map<String, String> bundle = byLanguage.get(language);
        if (bundle != null) {
            final String value = bundle.get(key);
            if (value != null) {
                return value;
            }
        }

        final Map<String, String> fallback = byLanguage.get(Locales.tag(Locales.DEFAULT));
        final String value = fallback == null ? null : fallback.get(key);
        if (value != null) {
            return value;
        }

        reportMissing(key);
        return key;
    }

    /**
     * Looks a key up and substitutes named parameters written as <code>{name}</code>.
     *
     * @param locale     the language wanted; {@code null} means English
     * @param key        the message key
     * @param parameters name/value pairs, e.g. {@code format(locale, "greeting", "name", player)}
     * @return the formatted message
     * @throws IllegalArgumentException if {@code parameters} does not have an even length
     */
    public String format(final Locale locale, final String key, final Object... parameters) {
        if (parameters == null || parameters.length == 0) {
            return get(locale, key);
        }
        if (parameters.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "format() takes name/value pairs, got " + parameters.length + " arguments for key " + key);
        }

        final Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < parameters.length; index += 2) {
            map.put(String.valueOf(parameters[index]), parameters[index + 1]);
        }
        return format(locale, key, map);
    }

    /**
     * Looks a key up and substitutes named parameters written as <code>{name}</code>.
     * <p>
     * Substitution is a single left-to-right pass over the template, so a value that itself
     * contains braces is never re-scanned - a player name of <code>{name}</code> cannot expand
     * into anything. A placeholder with no matching parameter is left in the text rather than
     * blanked, so it is visible in a screenshot.
     * </p>
     *
     * @param locale     the language wanted; {@code null} means English
     * @param key        the message key
     * @param parameters the parameters by name
     * @return the formatted message
     */
    public String format(final Locale locale, final String key, final Map<String, ?> parameters) {
        final String template = get(locale, key);
        if (parameters == null || parameters.isEmpty() || template.indexOf('{') < 0) {
            return template;
        }

        final StringBuilder out = new StringBuilder(template.length() + 16);
        int cursor = 0;
        while (cursor < template.length()) {
            final int open = template.indexOf('{', cursor);
            if (open < 0) {
                out.append(template, cursor, template.length());
                break;
            }
            final int close = template.indexOf('}', open + 1);
            if (close < 0) {
                out.append(template, cursor, template.length());
                break;
            }

            final String name = template.substring(open + 1, close);
            out.append(template, cursor, open);
            if (parameters.containsKey(name)) {
                out.append(parameters.get(name));
            } else {
                out.append('{').append(name).append('}');
            }
            cursor = close + 1;
        }

        return out.toString();
    }

    /**
     * @param locale the language
     * @param key    the message key
     * @return whether that language has its own translation for the key - a fallback to English
     *         counts as {@code false}
     */
    public boolean hasTranslation(final Locale locale, final String key) {
        final Map<String, String> bundle = byLanguage.get(Locales.tag(locale));
        return bundle != null && bundle.containsKey(key);
    }

    /** @return the languages this bundle actually loaded a file for; always contains {@code en} */
    public Set<String> languages() {
        return byLanguage.keySet();
    }

    private void reportMissing(final String key) {
        // Once per key, ever. A missing key on the login path would otherwise log per join.
        if (reportedMissing.add(key)) {
            LOGGER.warn("Missing message key '{}' in bundle {} - falling back to the key itself", key, root);
        }
    }
}
