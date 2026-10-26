package eu.nordtal.season.messages;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.messages.context.Contexts;
import eu.nordtal.season.messages.context.MessageEnvironment;
import eu.nordtal.season.messages.spec.MessageSchema;
import eu.nordtal.season.messages.spec.TextFormat;
import eu.nordtal.season.messages.text.Declaration;
import eu.nordtal.season.messages.text.Filling;
import eu.nordtal.season.messages.text.MessageCheck;
import eu.nordtal.season.messages.text.MessageSyntaxException;
import eu.nordtal.season.messages.text.MessageText;
import eu.nordtal.season.messages.text.Piece;
import eu.nordtal.season.messages.text.PlainText;
import eu.nordtal.season.messages.value.Kind;
import eu.nordtal.season.messages.value.Words;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
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
 * The texts of season 2 by language and key. A lookup falls back to English, then to the key.
 * This class renders plain text, and {@code :message-rendering} renders components from the same
 * {@link Prepared} pieces. The README describes the layers over the packaged texts.
 */
public final class Messages {

    private static final Logger LOGGER = LoggerFactory.getLogger(Messages.class);

    /** The bundle of the words that show values. It lies under every other bundle. */
    public static final String VALUES = "values";

    public static final String TERMS = "terms";

    /** The bundles of the building blocks, which lie under every other bundle so any text can name them. */
    private static final List<Class<?>> BLOCK_SPECS = List.of(ValueMessages.class, TermMessages.class);

    public static final List<String> BLOCKS =
            BLOCK_SPECS.stream().map(MessageSchema::bundle).toList();

    /** The bundle names, least specific first. */
    private final List<String> bundles;

    private final List<Locale> locales;
    private final MessageEnvironment environment;

    private final Map<String, Map<String, Map<String, List<String>>>> packaged;

    private final Map<String, MessageSchema.Bundle> schemas;

    /** The last bundle that declares a key decides its declaration and format. */
    private final Map<String, Declaration> declarations;

    private final Map<String, TextFormat> formats;

    private volatile List<MessageOverride> overrides = List.of();

    private volatile Map<String, Map<String, List<MessageText>>> byLanguage;

    /** The keys and the pairs of key and placeholder already logged. */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    private Messages(
            final List<String> bundles,
            final List<Locale> locales,
            final MessageEnvironment environment,
            final Map<String, Map<String, Map<String, List<String>>>> packaged,
            final Map<String, MessageSchema.Bundle> schemas,
            final List<MessageOverride> overrides) {
        this.bundles = bundles;
        this.locales = locales;
        this.environment = environment;
        this.packaged = packaged;
        this.schemas = schemas;
        this.declarations = declarations(bundles, schemas);
        this.formats = formats(bundles, schemas);
        this.byLanguage = compose(overrides);
        this.overrides = overrides;
    }

    /**
     * Loads a bundle with the class loader of this class.
     *
     * @param locales English is always loaded.
     * @throws IllegalStateException if the English file is missing
     * @throws UncheckedIOException  if a file exists but cannot be read
     */
    public static Messages load(final String root, final Locale... locales) {
        return load(Messages.class.getClassLoader(), root, locales);
    }

    /**
     * Loads a bundle with the given class loader, such as the one of a Paper plugin.
     *
     * @param locales English is always loaded.
     * @throws IllegalStateException if the English file is missing
     * @throws UncheckedIOException  if a file exists but cannot be read
     */
    public static Messages load(final ClassLoader classLoader, final String root, final Locale... locales) {
        return load(classLoader, List.of(Objects.requireNonNull(root, "root")), locales);
    }

    /**
     * Loads several bundles as one over the {@code values} bundle. A later root wins.
     *
     * @param roots   the resource directories, least specific first, at least one
     * @param locales English is always loaded.
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
        final List<String> named = roots.stream().map(Messages::bundleOf).toList();
        final List<String> bundles = new ArrayList<>(BLOCKS);
        named.stream().filter(bundle -> !BLOCKS.contains(bundle)).forEach(bundles::add);
        final List<Locale> loaded = withDefault(locales);
        final Map<String, Map<String, Map<String, List<String>>>> packaged = new LinkedHashMap<>();
        final Map<String, MessageSchema.Bundle> schemas = new LinkedHashMap<>();
        boolean english = false;
        for (final String bundle : bundles) {
            // A plugin's class loader may not see the bundles of this jar first.
            final ClassLoader loader = BLOCKS.contains(bundle) ? Messages.class.getClassLoader() : classLoader;
            final Map<String, Map<String, List<String>>> byLanguage = new LinkedHashMap<>();
            for (final Locale locale : loaded) {
                final Map<String, List<String>> texts = PackagedTexts.read(loader, bundle, Locales.tag(locale));
                if (texts != null) {
                    byLanguage.put(Locales.tag(locale), texts);
                }
            }
            english |= (!BLOCKS.contains(bundle) || named.contains(bundle))
                    && byLanguage.containsKey(Locales.tag(Locales.DEFAULT));
            packaged.put(bundle, Map.copyOf(byLanguage));
            final MessageSchema.Bundle schema = schemaOf(loader, bundle);
            if (schema != null) {
                schemas.put(bundle, schema);
            }
        }
        if (!english) {
            throw new IllegalStateException("No " + Locales.tag(Locales.DEFAULT) + ".properties in any of " + roots
                    + "; English is the fallback for every other language and must exist");
        }
        return new Messages(
                List.copyOf(bundles),
                loaded,
                MessageEnvironment.NONE,
                Map.copyOf(packaged),
                Map.copyOf(schemas),
                List.of());
    }

    /** Maps {@code messages/smp} and {@code messages/smp/} to the bundle {@code smp}. */
    private static String bundleOf(final String root) {
        final String trimmed = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
        if (!trimmed.startsWith("messages/") || trimmed.indexOf('/', "messages/".length()) >= 0) {
            throw new IllegalArgumentException(root + " is not a bundle: every bundle is a directory messages/<name>");
        }
        return trimmed.substring("messages/".length());
    }

    /** Returns the schema in the jar, or {@code null} for a bundle without one, as in a test. */
    private static MessageSchema.@Nullable Bundle schemaOf(final ClassLoader classLoader, final String bundle) {
        final String resource = "messages/" + bundle + "/schema.json";
        try (InputStream stream = classLoader.getResourceAsStream(resource)) {
            return stream == null ? null : MessageSchema.decode(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (final IOException exception) {
            throw new UncheckedIOException("Cannot read the message schema " + resource, exception);
        }
    }

    private static Map<String, Declaration> declarations(
            final List<String> bundles, final Map<String, MessageSchema.Bundle> schemas) {
        final Map<String, Declaration> declared = new HashMap<>();
        for (final String bundle : bundles) {
            final MessageSchema.Bundle schema = schemas.get(bundle);
            if (schema != null) {
                schema.messages().forEach(entry -> declared.put(entry.key(), schema.declaration(entry)));
            }
        }
        return Map.copyOf(declared);
    }

    /** Returns what the texts of each building block may name, by key, as their specs declare it. */
    public static Map<String, Declaration> blocks() {
        return Blocks.DECLARED;
    }

    private static final class Blocks {

        private static final Map<String, Declaration> DECLARED = declared();

        private Blocks() {}

        private static Map<String, Declaration> declared() {
            final Map<String, Declaration> declared = new HashMap<>();
            for (final Class<?> spec : BLOCK_SPECS) {
                final MessageSchema.Bundle schema = MessageSchema.of(spec);
                schema.messages().forEach(entry -> declared.put(entry.key(), schema.declaration(entry)));
            }
            return Map.copyOf(declared);
        }
    }

    /** Returns the building blocks as a reader of {@code language} sees them, overrides included. */
    public Filling.Blocks blocks(final Locale language) {
        return key -> {
            final Declaration declaration = Blocks.DECLARED.get(key);
            return declaration == null ? null : new Filling.Block(text(language, key), declaration.values());
        };
    }

    private static Map<String, TextFormat> formats(
            final List<String> bundles, final Map<String, MessageSchema.Bundle> schemas) {
        final Map<String, TextFormat> declared = new HashMap<>();
        for (final String bundle : bundles) {
            final MessageSchema.Bundle schema = schemas.get(bundle);
            if (schema != null) {
                schema.messages().forEach(entry -> declared.put(entry.key(), entry.format()));
            }
        }
        return Map.copyOf(declared);
    }

    /**
     * Returns the same bundles for one process, whose globals and time zone a message can name.
     * A process calls it once at startup.
     */
    public Messages within(final MessageEnvironment environment) {
        return new Messages(
                bundles, locales, Objects.requireNonNull(environment, "environment"), packaged, schemas, overrides);
    }

    public MessageEnvironment environment() {
        return environment;
    }

    /** Returns the loaded bundles and those that a key moved out of. */
    public Set<String> bundles() {
        final Set<String> named = new TreeSet<>(bundles);
        formerNames().forEach(name -> named.add(name.substring(0, name.indexOf('/'))));
        return Set.copyOf(named);
    }

    private List<String> formerNames() {
        final List<String> names = new ArrayList<>();
        schemas.forEach((bundle, schema) -> schema.messages().forEach(entry -> formerOf(bundle, entry, names)));
        return names;
    }

    private static void formerOf(final String bundle, final MessageSchema.Entry entry, final List<String> into) {
        if (entry.formerly() != null) {
            entry.formerly().forEach(name -> into.add(name.contains("/") ? name : bundle + "/" + name));
        }
    }

    /**
     * Layers an admin's overrides over the packaged bundles and replaces the rows layered before.
     * The rows of a key replace its packaged variants in their language, those of a former name if
     * the current name has none. Rows of undeclared keys and stale or refused rows are left out.
     */
    public void override(final List<MessageOverride> rows) {
        final List<MessageOverride> taken = List.copyOf(rows);
        byLanguage = compose(taken);
        overrides = taken;
        reported.clear();
    }

    private Map<String, Map<String, List<MessageText>>> compose(final List<MessageOverride> rows) {
        // The rows by bundle/key, language and variant, so a key's variants are replaced together.
        final Map<String, Map<String, Map<Integer, MessageOverride>>> stored = new HashMap<>();
        for (final MessageOverride row : rows) {
            stored.computeIfAbsent(row.bundle() + "/" + row.key(), ignored -> new HashMap<>())
                    .computeIfAbsent(row.language(), ignored -> new TreeMap<>())
                    .put(row.variant(), row);
        }
        final Map<String, Map<String, List<MessageText>>> composed = new LinkedHashMap<>();
        for (final Locale locale : locales) {
            final String language = Locales.tag(locale);
            final Map<String, List<MessageText>> merged = new HashMap<>();
            boolean any = false;
            // Least specific first, so a key of the process wins over a shared bundle's.
            for (final String bundle : bundles) {
                final Map<String, Map<String, List<String>>> shipped = Objects.requireNonNull(packaged.get(bundle));
                final Map<String, List<String>> own = shipped.get(language);
                if (own != null) {
                    own.forEach((key, texts) -> merged.put(key, parsed(bundle, key, texts)));
                    any |= !BLOCKS.contains(bundle);
                }
                final Set<String> declared = new HashSet<>();
                shipped.values().forEach(texts -> declared.addAll(texts.keySet()));
                for (final String key : declared) {
                    final List<MessageOverride> taken = overridden(stored, bundle, key, language);
                    if (taken == null) {
                        continue;
                    }
                    final List<String> texts =
                            taken.stream().map(MessageOverride::text).toList();
                    if (fresh(bundle, key, language, taken.getFirst(), own == null ? null : own.get(key))
                            && valid(bundle, key, language, texts)) {
                        merged.put(key, parsed(bundle, key, texts));
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

    /**
     * Returns whether the override was written over the packaged texts of this jar.
     * A stale override is logged, and the packaged text shows until an admin saves it again.
     */
    private static boolean fresh(
            final String bundle,
            final String key,
            final String language,
            final MessageOverride row,
            final @Nullable List<String> packaged) {
        if (!row.staleOver(packaged)) {
            return true;
        }
        LOGGER.warn(
                "The override of {}/{} in {} was written over a packaged text this release changed, so the packaged"
                        + " text shows until an admin takes the override over again",
                bundle,
                key,
                language);
        return false;
    }

    private boolean valid(final String bundle, final String key, final String language, final List<String> texts) {
        final Declaration declaration = declarations.get(key);
        if (declaration == null) {
            return true;
        }
        for (final String text : texts) {
            final List<String> errors = MessageCheck.errors(text, declaration, MessageCheck.Mode.OVERRIDE);
            if (!errors.isEmpty()) {
                LOGGER.warn(
                        "The override of {}/{} in {} is refused, so the packaged text shows: {}",
                        bundle,
                        key,
                        language,
                        String.join("; ", errors));
                return false;
            }
        }
        return true;
    }

    private List<MessageText> parsed(final String bundle, final String key, final List<String> texts) {
        final boolean markup = markup(key);
        final List<MessageText> parsed = new ArrayList<>(texts.size());
        for (final String text : texts) {
            try {
                parsed.add(MessageText.parse(text, markup));
            } catch (final MessageSyntaxException e) {
                // Only a jar built around the check gets here.
                LOGGER.warn("The text of {}/{} cannot be read and shows as written: {}", bundle, key, e.getMessage());
                parsed.add(MessageText.verbatim(text, markup));
            }
        }
        return List.copyOf(parsed);
    }

    private boolean markup(final String key) {
        final Declaration declaration = declarations.get(key);
        return declaration == null || declaration.markup();
    }

    private @Nullable List<MessageOverride> overridden(
            final Map<String, Map<String, Map<Integer, MessageOverride>>> stored,
            final String bundle,
            final String key,
            final String language) {
        final List<String> names = new ArrayList<>();
        names.add(bundle + "/" + key);
        final MessageSchema.Bundle schema = schemas.get(bundle);
        if (schema != null) {
            schema.messages().stream()
                    .filter(entry -> entry.key().equals(key))
                    .forEach(entry -> formerOf(bundle, entry, names));
        }
        for (final String name : names) {
            final Map<String, Map<Integer, MessageOverride>> byLanguage = stored.get(name);
            final Map<Integer, MessageOverride> variants = byLanguage == null ? null : byLanguage.get(language);
            if (variants != null && !variants.isEmpty()) {
                return List.copyOf(variants.values());
            }
        }
        return null;
    }

    /**
     * Returns the packaged texts of a key in a language, or an empty list.
     *
     * @param bundle the bundle that declares the key
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

    /**
     * Returns the message of {@code key} with an example value for each of its own placeholders.
     * {@link Declaration#example} fills them. A key no bundle here declares takes the given values as text.
     */
    public MessageRef example(final String key, final Map<String, String> given) {
        final Declaration declaration = declarations.get(key);
        return new MessageRef(
                key, declaration == null ? Map.<String, Object>copyOf(given) : declaration.example(given));
    }

    /** Returns the text of the key as written, falling back to English, then to the key. */
    public String get(final Locale locale, final String key) {
        return text(locale, key).source();
    }

    /** Returns a random text of the key, falling back to English, then to the key. */
    private MessageText text(final @Nullable Locale locale, final String key) {
        Objects.requireNonNull(key, "key");
        final Map<String, List<MessageText>> bundle =
                byLanguage.get(Locales.tag(locale == null ? Locales.DEFAULT : locale));
        List<MessageText> variants = bundle == null ? null : bundle.get(key);
        if (variants == null) {
            final Map<String, List<MessageText>> fallback = byLanguage.get(Locales.tag(Locales.DEFAULT));
            variants = fallback == null ? null : fallback.get(key);
        }
        if (variants == null) {
            if (reported.add(key)) {
                LOGGER.warn("Missing message key '{}' in bundle(s) {} - falling back to the key itself", key, bundles);
            }
            return MessageText.verbatim(key, false);
        }
        return variants.size() == 1
                ? variants.getFirst()
                : variants.get(ThreadLocalRandom.current().nextInt(variants.size()));
    }

    /**
     * A message for one reader, with its choices made and its values found.
     *
     * @param format tells a target whether to escape the values
     */
    public record Prepared(List<Piece> pieces, Locale language, ZoneId zone, TextFormat format, Words words) {

        public boolean markup() {
            return format == TextFormat.MINIMESSAGE;
        }
    }

    public Prepared prepare(final Viewer viewer, final MessageRef message) {
        return prepare(viewer, message, text(viewer.language(), message.key()));
    }

    /**
     * Returns the message prepared from a text that an admin tries in place of the key's text.
     * No override holds the text.
     *
     * @throws MessageSyntaxException if the text cannot be parsed
     */
    public Prepared prepare(final Viewer viewer, final MessageRef message, final String text) {
        return prepare(viewer, message, MessageText.parse(text, markup(message.key())));
    }

    /**
     * Returns a text that no key holds, such as a document's, prepared with its values.
     *
     * @param kinds each value's declared kind, which a missing value shows the replacement word of
     * @param source names the text in the log when a value is missing
     * @throws MessageSyntaxException if the text cannot be parsed
     */
    public Prepared prepare(
            final Viewer viewer,
            final String text,
            final Map<String, ?> values,
            final Map<String, Kind> kinds,
            final TextFormat format,
            final String source) {
        final ZoneId zone = viewer.zone() == null ? environment.zone() : viewer.zone();
        final List<Piece> pieces = Filling.fill(
                MessageText.parse(text, format == TextFormat.MINIMESSAGE),
                Contexts.flatten(values, environment, viewer.player()),
                kinds,
                name -> reportMissingValue(source, name),
                blocks(viewer.language()));
        return new Prepared(
                pieces, viewer.language(), zone, format, words(new Viewer(viewer.language(), zone, viewer.player())));
    }

    private Prepared prepare(final Viewer viewer, final MessageRef message, final MessageText text) {
        final Locale language = viewer.language();
        final ZoneId zone = viewer.zone() == null ? environment.zone() : viewer.zone();
        final Map<String, Object> values = Contexts.flatten(message.args(), environment, viewer.player());
        final Declaration declaration = declarations.get(message.key());
        final List<Piece> pieces = Filling.fill(
                text,
                values,
                declaration == null ? Map.<String, Kind>of() : declaration.values(),
                name -> reportMissingValue(message.key(), name),
                blocks(language));
        return new Prepared(
                pieces,
                language,
                zone,
                format(message.key(), text),
                words(new Viewer(language, zone, viewer.player())));
    }

    private TextFormat format(final String key, final MessageText text) {
        if (text.markup()) {
            return TextFormat.MINIMESSAGE;
        }
        final TextFormat declared = formats.get(key);
        return declared == null || declared == TextFormat.MINIMESSAGE ? TextFormat.PLAIN : declared;
    }

    public String format(final @Nullable Locale locale, final MessageRef message) {
        return format(Viewer.of(locale), message);
    }

    /** Renders the message as plain text, such as for a console or a log. */
    public String format(final Viewer viewer, final MessageRef message) {
        final Prepared prepared = prepare(viewer, message);
        return PlainText.of(prepared.pieces(), prepared.language(), prepared.zone(), prepared.words());
    }

    private Words words(final Viewer reader) {
        final Locale language = reader.language();
        final ZoneId zone = Objects.requireNonNull(reader.zone(), "zone");
        return new Words() {
            @Override
            public String word(final String key, final Map<String, Object> values) {
                final String name = VALUES + "." + key;
                final MessageText text = text(language, name);
                final Declaration declaration = declarations.get(name);
                final List<Piece> pieces = Filling.fill(
                        text,
                        values,
                        declaration == null ? Map.<String, Kind>of() : declaration.values(),
                        missing -> reportMissingValue(name, missing),
                        blocks(language));
                return PlainText.of(pieces, language, zone, (inner, ignored) -> "");
            }

            @Override
            public String message(final MessageRef message) {
                return format(reader, message);
            }
        };
    }

    private void reportMissingValue(final String key, final String name) {
        if (reported.add(key + "{" + name + "}")) {
            LOGGER.warn("{} was rendered without a value for {{}}, so its replacement word shows", key, name);
        }
    }

    /**
     * Returns the parsed texts of every key in a language.
     * The overrides are layered, and English fills a key the language lacks.
     */
    public Map<String, List<MessageText>> texts(final Locale locale) {
        final Map<String, List<MessageText>> texts =
                new TreeMap<>(byLanguage.getOrDefault(Locales.tag(Locales.DEFAULT), Map.of()));
        texts.putAll(byLanguage.getOrDefault(Locales.tag(locale), Map.of()));
        return texts;
    }

    /** Returns whether the language has its own text for the key, not the English fallback. */
    public boolean hasTranslation(final Locale locale, final String key) {
        final Map<String, List<MessageText>> bundle = byLanguage.get(Locales.tag(locale));
        return bundle != null && bundle.containsKey(key);
    }

    public List<Locale> locales() {
        return locales;
    }

    /** Returns the languages that have a file, always including {@code en}. */
    public Set<String> languages() {
        return byLanguage.keySet();
    }
}
