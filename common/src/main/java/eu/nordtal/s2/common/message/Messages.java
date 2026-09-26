package eu.nordtal.s2.common.message;

import eu.nordtal.s2.common.message.context.Contexts;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Looks up every user-visible string of season 2 by language and key, with English as fallback.
 *
 * Bundles are UTF-8 {@code .properties} files per language ({@code messages/access/en.properties}) with
 * dotted lowercase keys and named parameters in braces. Lookup falls back from the language to English to
 * the key itself, and never throws. An operator directory {@code plugins/<name>/messages/} is merged over
 * the packaged bundle key by key, so an override holds only the changed lines; keys no bundle declares are
 * reported by {@link #unknownOverrideKeys()}. {@link #reload()} replaces the map wholesale, in place.
 */
public final class Messages {

    private static final Logger LOGGER = LoggerFactory.getLogger(Messages.class);

    private final List<String> roots;
    private final ClassLoader classLoader;
    private final List<Locale> locales;

    /** The operator's override directory, or {@code null} when this bundle has none. */
    private final @Nullable Path overrides;

    /** Language tag to key to template; volatile because {@link #reload()} swaps it from any thread. */
    private volatile Map<String, Map<String, String>> byLanguage;

    /** Override keys no bundle declares. Replaced with the map above. */
    private volatile Set<String> unknownOverrideKeys;

    /** Keys already reported missing, so a hot loop logs once and not per call. */
    private final Set<String> reportedMissing = ConcurrentHashMap.newKeySet();

    private Messages(
            final List<String> roots,
            final ClassLoader classLoader,
            final @Nullable Path overrides,
            final List<Locale> locales) {
        this.roots = roots;
        this.classLoader = classLoader;
        this.overrides = overrides;
        this.locales = locales;
        reload();
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
        return load(classLoader, root, null, locales);
    }

    /**
     * Loads a bundle from a class loader and merges an operator's override directory over it.
     *
     * The directory is created if it is not there, together with a {@code README.txt} that says
     * what belongs in it - an empty folder in a data directory teaches nobody anything, and this is
     * the only place an operator would look for the mechanism.
     *
     * @param classLoader the loader to read the packaged bundle from
     * @param root        the resource directory, e.g. {@code messages/smp}
     * @param overrides   {@code plugins/<name>/messages}, or {@code null} for no override layer
     * @param locales     the languages to load; English is always loaded
     * @return the loaded bundle
     * @throws IllegalStateException if English is in neither layer
     * @throws UncheckedIOException  if a file exists but cannot be read
     */
    public static Messages load(
            final ClassLoader classLoader, final String root, final @Nullable Path overrides, final Locale... locales) {
        return load(classLoader, List.of(Objects.requireNonNull(root, "root")), overrides, locales);
    }

    /**
     * Loads several bundles as one, layered in the order given, with the override directory on top.
     *
     * Later roots win, so the shared {@code :commands} bundle goes first and a process can reword a line.
     *
     * @param roots     the resource directories, least specific first, at least one
     * @param overrides {@code plugins/<name>/messages}, or {@code null} for no override layer
     * @param locales   the languages to load; English is always loaded
     * @throws IllegalStateException if no root supplies English
     * @throws UncheckedIOException  if a file exists but cannot be read
     */
    public static Messages load(
            final ClassLoader classLoader,
            final List<String> roots,
            final @Nullable Path overrides,
            final Locale... locales) {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(roots, "roots");
        if (roots.isEmpty()) {
            throw new IllegalArgumentException(
                    "a Messages with no bundle would answer every key" + " with the key itself, silently");
        }

        final List<String> normalised = roots.stream()
                .map(root -> root.endsWith("/") ? root.substring(0, root.length() - 1) : root)
                .toList();
        if (overrides != null) {
            prepare(overrides, String.join(", ", normalised));
        }
        return new Messages(normalised, classLoader, overrides, withDefault(locales));
    }

    /**
     * Re-reads the packaged bundle and the override directory, and swaps the result in.
     *
     * Every holder of this object keeps working against the same reference, which is the point:
     * a {@code Messages} is handed to listeners, HUD renderers and commands at startup, and a
     * reload that produced a new instance would reach none of them.
     *
     * @throws IllegalStateException if English is in neither layer
     * @throws UncheckedIOException  if a file exists but cannot be read
     */
    public void reload() {
        final Map<String, Map<String, String>> loaded = new LinkedHashMap<>();
        final Set<String> unknown = new java.util.TreeSet<>();

        final Map<String, Map<String, String>> packagedByLanguage = readPackaged();
        // An override is unknown only when no language declares the key; German may override an English-only key.
        final Set<String> declaredAnywhere = new java.util.HashSet<>();
        packagedByLanguage.values().forEach(packaged -> declaredAnywhere.addAll(packaged.keySet()));

        for (final Locale locale : locales) {
            final String language = Locales.tag(locale);
            final Map<String, String> packaged = packagedByLanguage.get(language);
            final Map<String, String> operator = readOverride(overrides, language);

            if (packaged == null && operator == null) {
                if (language.equals(Locales.DEFAULT.getLanguage())) {
                    throw new IllegalStateException("No " + language + ".properties in any of " + roots
                            + "; English is the" + " fallback for every other language and must exist");
                }
                LOGGER.warn(
                        "No message bundle {}.properties in any of {} - {} falls back to English",
                        language,
                        roots,
                        language);
                continue;
            }

            final Map<String, String> merged = new HashMap<>(packaged == null ? Map.of() : packaged);
            if (operator != null) {
                operator.forEach((key, value) -> {
                    merged.put(key, value);
                    // An override for a key no bundle declares is a silent typo; collected so the module can log it.
                    if (!declaredAnywhere.contains(key)) {
                        unknown.add(language + "/" + key);
                    }
                });
            }
            loaded.put(language, Map.copyOf(merged));
        }

        byLanguage = Map.copyOf(loaded);
        unknownOverrideKeys = Set.copyOf(unknown);
        // A key that was missing before a reload may exist after one; keep reporting honest.
        reportedMissing.clear();
    }

    /** Reads every packaged bundle per language, layering the roots least specific first. */
    private Map<String, Map<String, String>> readPackaged() {
        final Map<String, Map<String, String>> packagedByLanguage = new LinkedHashMap<>();
        for (final Locale locale : locales) {
            final String language = Locales.tag(locale);
            // Least specific first, so a process's own key wins over the one it inherits from :commands.
            Map<String, String> packaged = null;
            for (final String root : roots) {
                final Map<String, String> fromRoot = read(classLoader, root, language);
                if (fromRoot == null) {
                    continue;
                }
                if (packaged == null) {
                    packaged = new HashMap<>(fromRoot);
                } else {
                    packaged.putAll(fromRoot);
                }
            }
            if (packaged != null) {
                packagedByLanguage.put(language, packaged);
            }
        }
        return packagedByLanguage;
    }

    /**
     * @return {@code <language>/<key>} for every override entry that overrode nothing - a typo or a
     *         key that has since been retired. <b>Not</b> an override of a key only another
     *         language's packaged bundle declares: that one works, so reporting it would send the
     *         operator hunting for a spelling mistake in a line they can watch taking effect
     */
    public Set<String> unknownOverrideKeys() {
        return unknownOverrideKeys;
    }

    /** @return the override directory this bundle merges, if it has one */
    public java.util.Optional<Path> overrideDirectory() {
        return java.util.Optional.ofNullable(overrides);
    }

    /** Creates the override directory and, once, the note that says what it is for. */
    private static void prepare(final Path directory, final String root) {
        try {
            Files.createDirectories(directory);
            final Path readme = directory.resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, """
                        This folder overrides individual message lines, key by key.

                        The messages this server ships with live in the jar, under %s.
                        Anything you put here wins over them - but only the keys you actually
                        write down. Every other line keeps following the jar, so an update that
                        adds or rewords a message still reaches players.

                        One file per language, named after the language:

                            en.properties
                            de.properties

                        UTF-8, and umlauts are written as umlauts - not as \\u00e4.

                        A key that no message bundle declares is stored and never used. The
                        server logs those at startup and after a reload, by name, so a typo
                        here shows up in the console instead of on nobody's screen.

                        Reload with the module's own reload command; no restart is needed.
                        """.formatted(root), StandardCharsets.UTF_8);
            }
        } catch (final IOException exception) {
            throw new UncheckedIOException("Cannot prepare the message override directory " + directory, exception);
        }
    }

    /** Reads {@code <overrides>/<language>.properties}, or {@code null} if there is none. */
    private static @Nullable Map<String, String> readOverride(final @Nullable Path overrides, final String language) {
        if (overrides == null) {
            return null;
        }
        final Path file = overrides.resolve(language + ".properties");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            final Properties properties = new Properties();
            properties.load(reader);
            final Map<String, String> entries = new HashMap<>(properties.size());
            properties.forEach((key, value) -> entries.put(String.valueOf(key), String.valueOf(value)));
            return entries;
        } catch (final IOException exception) {
            throw new UncheckedIOException("Cannot read message override " + file, exception);
        }
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

    private static @Nullable Map<String, String> read(
            final ClassLoader classLoader, final String root, final String language) {
        final String resource = root + "/" + language + ".properties";
        try (InputStream stream = classLoader.getResourceAsStream(resource)) {
            if (stream == null) {
                return null;
            }

            // Properties.load(InputStream) is ISO-8859-1 and would garble every umlaut.
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
     *
     * Substitution is a single left-to-right pass over the template, so a value that itself
     * contains braces is never re-scanned - a player name of <code>{name}</code> cannot expand
     * into anything. A placeholder with no matching parameter is left in the text rather than
     * blanked, so it is visible in a screenshot.
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
     * Renders a message a spec chose as plain text, with context and global placeholders substituted.
     *
     * @param locale the language wanted; {@code null} means English
     */
    public String format(final Locale locale, final MessageRef message) {
        return format(locale, message.key(), Contexts.flatten(message.args()));
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
            LOGGER.warn("Missing message key '{}' in bundle(s) {} - falling back to the key itself", key, roots);
        }
    }
}
