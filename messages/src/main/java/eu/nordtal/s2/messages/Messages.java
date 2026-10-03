package eu.nordtal.s2.messages;

import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.common.language.Locales;
import eu.nordtal.s2.messages.context.Contexts;
import eu.nordtal.s2.messages.context.MessageEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Looks up every user-visible string of season 2 by language and key, with English as fallback.
 * Lookup falls back to English, then to the key, and never throws. An admin's overrides, rows of the database, are
 * layered over the packaged bundles with {@link #override}; a key with several texts answers one of them at random.
 */
public final class Messages {

    private static final Logger LOGGER = LoggerFactory.getLogger(Messages.class);

    /** The bundles, least specific first, by name: {@code smp} for {@code messages/smp}. */
    private final List<String> bundles;

    private final List<Locale> locales;
    private final MessageEnvironment environment;

    /** Bundle to language tag to key to its variants, as the jar ships them. */
    private final Map<String, Map<String, Map<String, List<String>>>> packaged;

    /** Bundle to key to the names it had before, as {@code bundle/key}. */
    private final Map<String, Map<String, List<String>>> formerly;

    /** The rows last layered, kept so {@link #within} carries them along. */
    private volatile List<MessageOverride> overrides = List.of();

    /** Language tag to key to its variants; volatile because {@link #override} swaps it from the hub's thread. */
    private volatile Map<String, Map<String, List<String>>> byLanguage;

    /** Keys already reported missing, so a hot loop logs once and not per call. */
    private final Set<String> reportedMissing = ConcurrentHashMap.newKeySet();

    private Messages(
            final List<String> bundles,
            final List<Locale> locales,
            final MessageEnvironment environment,
            final Map<String, Map<String, Map<String, List<String>>>> packaged,
            final Map<String, Map<String, List<String>>> formerly,
            final List<MessageOverride> overrides) {
        this.bundles = bundles;
        this.locales = locales;
        this.environment = environment;
        this.packaged = packaged;
        this.formerly = formerly;
        this.byLanguage = compose(overrides);
        this.overrides = overrides;
    }

    /**
     * Loads a bundle from the classpath of this class's own class loader.
     *
     * @param root    the resource directory, e.g. {@code messages/access}
     * @param locales the languages to load; English is always loaded
     * @throws IllegalStateException if the English file is missing
     * @throws UncheckedIOException  if a file exists but cannot be read
     */
    public static Messages load(final String root, final Locale... locales) {
        return load(Messages.class.getClassLoader(), root, locales);
    }

    /**
     * Loads a bundle from a specific class loader, such as a Paper plugin's.
     *
     * @param root    the resource directory, e.g. {@code messages/access}
     * @param locales the languages to load; English is always loaded
     * @throws IllegalStateException if the English file is missing
     * @throws UncheckedIOException  if a file exists but cannot be read
     */
    public static Messages load(final ClassLoader classLoader, final String root, final Locale... locales) {
        return load(classLoader, List.of(Objects.requireNonNull(root, "root")), locales);
    }

    /**
     * Loads several bundles as one, later roots winning.
     *
     * @param roots   the resource directories, least specific first, at least one
     * @param locales the languages to load; English is always loaded
     * @throws IllegalStateException if no root supplies English
     * @throws UncheckedIOException  if a file exists but cannot be read
     */
    public static Messages load(final ClassLoader classLoader, final List<String> roots, final Locale... locales) {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(roots, "roots");
        if (roots.isEmpty()) {
            throw new IllegalArgumentException(
                    "a Messages with no bundle would answer every key" + " with the key itself, silently");
        }
        final List<String> bundles = roots.stream().map(Messages::bundleOf).toList();
        final List<Locale> loaded = withDefault(locales);
        final Map<String, Map<String, Map<String, List<String>>>> packaged = new LinkedHashMap<>();
        final Map<String, Map<String, List<String>>> formerly = new LinkedHashMap<>();
        for (final String bundle : bundles) {
            final Map<String, Map<String, List<String>>> byLanguage = new LinkedHashMap<>();
            for (final Locale locale : loaded) {
                final Map<String, List<String>> texts = PackagedTexts.read(classLoader, bundle, Locales.tag(locale));
                if (texts != null) {
                    byLanguage.put(Locales.tag(locale), texts);
                }
            }
            packaged.put(bundle, Map.copyOf(byLanguage));
            formerly.put(bundle, formerNames(classLoader, bundle));
        }
        final boolean english =
                packaged.values().stream().anyMatch(byLanguage -> byLanguage.containsKey(Locales.tag(Locales.DEFAULT)));
        if (!english) {
            throw new IllegalStateException("No " + Locales.tag(Locales.DEFAULT) + ".properties in any of " + roots
                    + "; English is the fallback for every other language and must exist");
        }
        return new Messages(
                bundles, loaded, MessageEnvironment.NONE, Map.copyOf(packaged), Map.copyOf(formerly), List.of());
    }

    /** {@code messages/smp} and {@code messages/smp/} both name the bundle {@code smp}. */
    private static String bundleOf(final String root) {
        final String trimmed = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
        if (!trimmed.startsWith("messages/") || trimmed.indexOf('/', "messages/".length()) >= 0) {
            throw new IllegalArgumentException(root + " is not a bundle: every bundle is a directory messages/<name>");
        }
        return trimmed.substring("messages/".length());
    }

    /** The former names the bundle's schema records, by key; none for a bundle without a schema, as in a test. */
    private static Map<String, List<String>> formerNames(final ClassLoader classLoader, final String bundle) {
        final String resource = "messages/" + bundle + "/schema.json";
        try (InputStream stream = classLoader.getResourceAsStream(resource)) {
            if (stream == null) {
                return Map.of();
            }
            final Schema schema = Json.decode(new InputStreamReader(stream, StandardCharsets.UTF_8), Schema.class);
            final Map<String, List<String>> names = new HashMap<>();
            for (final SchemaMessage message : schema.messages()) {
                final List<String> former = message.formerly();
                if (former == null || former.isEmpty()) {
                    continue;
                }
                names.put(
                        message.key(),
                        former.stream()
                                .map(name -> name.contains("/") ? name : bundle + "/" + name)
                                .toList());
            }
            return Map.copyOf(names);
        } catch (final IOException exception) {
            throw new UncheckedIOException("Cannot read the message schema " + resource, exception);
        }
    }

    /** The part of a {@code schema.json} a load reads. */
    private record Schema(List<SchemaMessage> messages) {}

    /** One message of a schema; {@code formerly} is absent for a key that was never renamed. */
    private record SchemaMessage(String key, @Nullable List<String> formerly) {}

    /**
     * Returns the same bundles for one process, whose {@code server} and {@code season} every message can name.
     * A process calls it once at startup; the overrides layered so far come along.
     */
    public Messages within(final MessageEnvironment environment) {
        return new Messages(
                bundles, locales, Objects.requireNonNull(environment, "environment"), packaged, formerly, overrides);
    }

    /** Returns the process this instance renders for. */
    public MessageEnvironment environment() {
        return environment;
    }

    /** Returns every bundle whose overrides apply here: the loaded ones and those a key was moved out of. */
    public Set<String> bundles() {
        final Set<String> named = new TreeSet<>(bundles);
        formerly.values()
                .forEach(keys -> keys.values()
                        .forEach(names -> names.forEach(name -> named.add(name.substring(0, name.indexOf('/'))))));
        return Set.copyOf(named);
    }

    /**
     * Layers an admin's overrides over the packaged bundles, replacing the rows layered before.
     * A key's rows in a language replace all its packaged variants there; one under a former name applies where the
     * current name has none, and one naming a key no bundle here declares is left out.
     */
    public void override(final List<MessageOverride> rows) {
        final List<MessageOverride> taken = List.copyOf(rows);
        byLanguage = compose(taken);
        overrides = taken;
        reportedMissing.clear();
    }

    private Map<String, Map<String, List<String>>> compose(final List<MessageOverride> rows) {
        // Bundle/key to language to variant to text, so a key's variants are replaced as one.
        final Map<String, Map<String, Map<Integer, String>>> stored = new HashMap<>();
        for (final MessageOverride row : rows) {
            stored.computeIfAbsent(row.bundle() + "/" + row.key(), ignored -> new HashMap<>())
                    .computeIfAbsent(row.language(), ignored -> new TreeMap<>())
                    .put(row.variant(), row.text());
        }
        final Map<String, Map<String, List<String>>> composed = new LinkedHashMap<>();
        for (final Locale locale : locales) {
            final String language = Locales.tag(locale);
            final Map<String, List<String>> merged = new HashMap<>();
            boolean any = false;
            // Least specific first, so a process's own key wins over the one it inherits from a shared bundle.
            for (final String bundle : bundles) {
                final Map<String, Map<String, List<String>>> shipped = Objects.requireNonNull(packaged.get(bundle));
                final Map<String, List<String>> own = shipped.get(language);
                if (own != null) {
                    merged.putAll(own);
                    any = true;
                }
                final Set<String> declared = new HashSet<>();
                shipped.values().forEach(texts -> declared.addAll(texts.keySet()));
                for (final String key : declared) {
                    final List<String> taken = overridden(stored, bundle, key, language);
                    if (taken != null) {
                        merged.put(key, taken);
                        any = true;
                    }
                }
            }
            if (any) {
                composed.put(language, Map.copyOf(merged));
            } else if (!language.equals(Locales.tag(Locales.DEFAULT))) {
                LOGGER.warn(
                        "No message bundle {}.properties in any of {} - {} falls back to English",
                        language,
                        bundles,
                        language);
            }
        }
        return Map.copyOf(composed);
    }

    /** The texts stored for a key in a language, under its name or else a former one, or {@code null}. */
    private @Nullable List<String> overridden(
            final Map<String, Map<String, Map<Integer, String>>> stored,
            final String bundle,
            final String key,
            final String language) {
        final List<String> names = new ArrayList<>();
        names.add(bundle + "/" + key);
        names.addAll(Objects.requireNonNull(formerly.get(bundle)).getOrDefault(key, List.of()));
        for (final String name : names) {
            final Map<String, Map<Integer, String>> byLanguage = stored.get(name);
            final Map<Integer, String> variants = byLanguage == null ? null : byLanguage.get(language);
            if (variants != null && !variants.isEmpty()) {
                return List.copyOf(variants.values());
            }
        }
        return null;
    }

    /**
     * Returns the packaged texts of a key in a language, as an override records them, or none.
     *
     * @param bundle the bundle that declares the key, such as {@code paper-common}
     */
    public List<String> packaged(final String bundle, final String key, final Locale locale) {
        final Map<String, Map<String, List<String>>> shipped = packaged.get(bundle);
        final Map<String, List<String>> texts = shipped == null ? null : shipped.get(Locales.tag(locale));
        return texts == null ? List.of() : texts.getOrDefault(key, List.of());
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

    /** Looks a key up without substitution, falling back to English and then to the key itself. */
    public String get(final Locale locale, final String key) {
        Objects.requireNonNull(key, "key");

        final String language = Locales.tag(locale);
        final Map<String, List<String>> bundle = byLanguage.get(language);
        if (bundle != null) {
            final List<String> variants = bundle.get(key);
            if (variants != null) {
                return oneOf(variants);
            }
        }

        final Map<String, List<String>> fallback = byLanguage.get(Locales.tag(Locales.DEFAULT));
        final List<String> variants = fallback == null ? null : fallback.get(key);
        if (variants != null) {
            return oneOf(variants);
        }

        reportMissing(key);
        return key;
    }

    /** One of a key's texts, at random where it has several. */
    private static String oneOf(final List<String> variants) {
        return variants.size() == 1
                ? variants.getFirst()
                : variants.get(ThreadLocalRandom.current().nextInt(variants.size()));
    }

    /**
     * Looks a key up and substitutes named parameters written as <code>{name}</code>.
     *
     * @param parameters name and value pairs, e.g. {@code format(locale, "greeting", "name", player)}
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
     * One left-to-right pass, so a value holding braces is never expanded; an unmatched placeholder stays visible.
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

    /** Renders a message a spec chose as plain text, with context and global placeholders substituted. */
    public String format(final Locale locale, final MessageRef message) {
        return format(locale, message.key(), Contexts.flatten(message.args(), environment));
    }

    /** Returns whether that language has its own translation for the key, not counting the English fallback. */
    public boolean hasTranslation(final Locale locale, final String key) {
        final Map<String, List<String>> bundle = byLanguage.get(Locales.tag(locale));
        return bundle != null && bundle.containsKey(key);
    }

    /** Returns the languages this process was loaded in, in their order, English first. */
    public List<Locale> locales() {
        return locales;
    }

    /** Returns the languages this bundle loaded a file for, always including {@code en}. */
    public Set<String> languages() {
        return byLanguage.keySet();
    }

    private void reportMissing(final String key) {
        // Once per key, so a missing key on the login path does not log per join.
        if (reportedMissing.add(key)) {
            LOGGER.warn("Missing message key '{}' in bundle(s) {} - falling back to the key itself", key, bundles);
        }
    }
}
