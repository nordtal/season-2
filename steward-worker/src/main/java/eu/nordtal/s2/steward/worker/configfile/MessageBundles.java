package eu.nordtal.s2.steward.worker.configfile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import eu.nordtal.s2.steward.worker.plan.JarName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the message bundles a module ships in its jar, merged with whatever an operator has
 * overridden on disk (steward/48).
 *
 * <p><b>The jar is the source of the packaged text, never the disk.</b> A plugin's data directory
 * only ever holds the keys somebody has already overridden - almost none of them, on a fresh
 * deployment - while the jar carries the whole bundle: {@code eu.nordtal.s2.common.message.Messages}
 * loads it from the classpath at runtime, and this class opens the same jar from the outside to draw
 * the same picture without a plugin class on this process's classpath. Editing a line in the
 * interface is what creates the override; before that edit, nothing about that key exists on disk at
 * all.</p>
 *
 * <h2>Finding the jar</h2>
 * A bundle is discovered from its override directory - a {@code messages/} directory under the
 * configs mount, which {@code Messages.load} creates (with its {@code README.txt}) the first time a
 * module starts, whether or not it has ever been used. The jar that goes with it is found by
 * {@link JarName#prefixOf}, the same rule {@code Installation} and {@code entrypoint.sh} already use
 * to name a jar by its artefact id: the plugin's own data directory (or the service name, for a
 * standalone jar with no {@code plugins/} layer) is the prefix, and it is looked for first among the
 * jars sitting directly in the service's configs directory - where a Paper or Velocity plugin's own
 * jar sits, next to its data folder - and then, for a service with none there, among the jars in the
 * matching volumes directory. That second lookup is the one discord-bot needs: its jar is not under
 * the configs mount at all.
 *
 * <h2>The merge</h2>
 * A module usually loads several roots as one bundle - {@code commands} plus its own root, a Paper
 * plugin also {@code paper-common} - and the single override directory beside it holds one
 * {@code en.properties} and one {@code de.properties} for all of them together, key by key
 * ({@link eu.nordtal.s2.common.message.Messages}'s own javadoc explains why: a whole-file override
 * would freeze the wording at the day it was copied). {@link #read} reproduces that merge by reading
 * every {@code messages/<root>/{en,de}.properties} entry the jar has and folding them into one map
 * per language, so the picture shown here is the one bundle the module itself would build.
 */
public final class MessageBundles {

    private static final Logger LOG = LoggerFactory.getLogger(MessageBundles.class);

    /** {@code messages/<root>/en.properties} or {@code messages/<root>/de.properties}, anywhere in a jar. */
    private static final Pattern BUNDLE_ENTRY = Pattern.compile("messages/([^/]+)/(en|de)\\.properties");

    /**
     * {@code messages/<root>/schema.json}: the names, placeholders and sections a root's message spec
     * declares, written into the jar at build time by {@code eu.nordtal.s2.common.message.spec.MessageSchema}.
     */
    private static final Pattern SCHEMA_ENTRY = Pattern.compile("messages/([^/]+)/schema\\.json");

    /** A placeholder as a message spec declares it: {@code {name}} for text, {@code <_name>} for a legacy tag. */
    private static final Pattern DECLARABLE = Pattern.compile("\\{([A-Za-z0-9_.-]+)}|<(_[A-Za-z0-9_-]+)>");

    /**
     * A parameter this project writes two ways: {@code {name}} - substituted by
     * {@code eu.nordtal.s2.common.message.Messages#format} - and {@code <_name>}, the underscored
     * MiniMessage tags the Paper plugins resolve their own placeholders through (Adventure's ordinary
     * formatting tags, {@code <bold>}, {@code <gray>}, carry no leading underscore and are
     * deliberately not matched: they are not filled in from data, so a line that drops one changes
     * how a message looks, never whether it still works).
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[A-Za-z0-9_.-]+}|<_[A-Za-z0-9_-]+>");

    /**
     * The directory a module's saved translations live in, beside its config. It makes a message
     * bundle here, and it keeps {@link ConfigFiles#discover} from listing the same files as configs.
     */
    static final String DIRECTORY = "messages";

    private MessageBundles() {
    }

    // -----------------------------------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------------------------------

    /**
     * Every message bundle under the configs mount.
     *
     * <p>Cheap on purpose, the same way {@link ConfigFiles#discover} is: it only looks for the
     * override directory and the jar beside it, never opens either. A bundle whose jar cannot be
     * found - a deployment mid-way through installing a module for the first time - is left out
     * with a warning rather than reported broken; there is nothing to show packaged text from yet.
     *
     * @param configsRoot the configs mount, e.g. {@code /configs}
     * @param volumesRoot the volumes mount, e.g. {@code /volumes} - where a standalone jar such as
     *                    discord-bot's actually lives; {@code null} to search the configs mount only
     * @return every bundle found, by service then module. <b>Empty if {@code configsRoot} does not
     *         exist</b>
     */
    public static @NotNull List<MessageBundleLocation> discover(final @NotNull Path configsRoot,
                                                                 final @Nullable Path volumesRoot) {
        if (!Files.isDirectory(configsRoot)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(configsRoot)) {
            return walk.filter(Files::isDirectory)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> DIRECTORY.equals(path.getFileName().toString()))
                    .map(path -> locationOf(configsRoot, volumesRoot, path))
                    .filter(location -> location != null)
                    .sorted(java.util.Comparator.comparing(MessageBundleLocation::service)
                            .thenComparing(MessageBundleLocation::module))
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot list the message bundles under " + configsRoot, e);
        }
    }

    private static @Nullable MessageBundleLocation locationOf(final Path configsRoot,
                                                              final @Nullable Path volumesRoot,
                                                              final Path messagesDirectory) {
        final Path relative = configsRoot.relativize(messagesDirectory);
        if (relative.getNameCount() < 2) {
            // "messages" lying directly at the mount's root, with no service directory above it -
            // not a shape this deployment produces, and there is no service to search a jar under.
            return null;
        }
        final String service = relative.getName(0).toString();
        final StringBuilder module = new StringBuilder();
        for (int i = 1; i < relative.getNameCount() - 1; i++) {
            if (!module.isEmpty()) {
                module.append('/');
            }
            module.append(relative.getName(i));
        }
        final String prefix = module.isEmpty() ? service : module.toString();
        final Path jar = findJar(configsRoot, volumesRoot, service, prefix);
        if (jar == null) {
            LOG.warn("{}: no jar named like \"{}\" under {}{} - this bundle cannot be shown yet",
                    messagesDirectory, prefix, configsRoot.resolve(service),
                    volumesRoot == null ? "" : " or " + volumesRoot.resolve(service));
            return null;
        }
        return new MessageBundleLocation(service, module.toString(), jar, messagesDirectory,
                Files.isWritable(messagesDirectory));
    }

    private static @Nullable Path findJar(final Path configsRoot, final @Nullable Path volumesRoot,
                                          final String service, final String prefix) {
        final Path fromConfigs = jarWithPrefix(configsRoot.resolve(service), prefix);
        if (fromConfigs != null) {
            return fromConfigs;
        }
        return volumesRoot == null ? null : jarWithPrefix(volumesRoot.resolve(service), prefix);
    }

    /** The one jar directly in {@code directory} (never a subdirectory) whose prefix matches. */
    private static @Nullable Path jarWithPrefix(final Path directory, final String prefix) {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.jar")) {
            for (final Path candidate : stream) {
                if (prefix.equals(JarName.prefixOf(candidate.getFileName().toString()))) {
                    return candidate;
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot list " + directory, e);
        }
        return null;
    }

    // -----------------------------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------------------------

    /**
     * Opens {@code location}'s jar and its override directory, and merges them into one bundle.
     *
     * @param location where to read from
     * @return the bundle, the schema's keys in its order, then the rest sorted
     * @throws IOException if the jar or an override file cannot be read
     */
    public static @NotNull MessageBundle read(final @NotNull MessageBundleLocation location) throws IOException {
        final Map<String, String> packagedEnglish = new HashMap<>();
        final Map<String, String> packagedGerman = new HashMap<>();
        // Root name to its schema, sorted so the order of the entries does not depend on the order
        // of the jar's directory.
        final Map<String, List<SchemaEntry>> schemas = new TreeMap<>();
        try (ZipFile jar = new ZipFile(location.jar().toFile())) {
            final Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                final Matcher schema = SCHEMA_ENTRY.matcher(entry.getName());
                if (schema.matches()) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        schemas.put(schema.group(1), readSchema(location, entry.getName(), in));
                    }
                    continue;
                }
                final Matcher matcher = BUNDLE_ENTRY.matcher(entry.getName());
                if (!matcher.matches()) {
                    continue;
                }
                final Map<String, String> target =
                        "en".equals(matcher.group(2)) ? packagedEnglish : packagedGerman;
                try (InputStream in = jar.getInputStream(entry)) {
                    target.putAll(readProperties(in));
                }
            }
        }
        final Map<String, SchemaEntry> described = new LinkedHashMap<>();
        schemas.values().forEach(list -> list.forEach(entry -> described.putIfAbsent(entry.key(), entry)));

        final Map<String, String> overrideEnglish = readOverride(location.overrideDirectory(), "en");
        final Map<String, String> overrideGerman = readOverride(location.overrideDirectory(), "de");

        // The schema's order first - the order of each English file, the one a person curated - and
        // everything it does not describe after it, sorted.
        final Set<String> undescribed = new TreeSet<>();
        undescribed.addAll(packagedEnglish.keySet());
        undescribed.addAll(packagedGerman.keySet());
        undescribed.addAll(overrideEnglish.keySet());
        undescribed.addAll(overrideGerman.keySet());
        undescribed.removeAll(described.keySet());
        final List<String> keys = new ArrayList<>(described.keySet());
        keys.addAll(undescribed);

        final List<MessageEntry> entries = new ArrayList<>(keys.size());
        for (final String key : keys) {
            final SchemaEntry schema = described.get(key);
            entries.add(new MessageEntry(key, packagedEnglish.get(key), packagedGerman.get(key),
                    overrideEnglish.get(key), overrideGerman.get(key),
                    packagedEnglish.containsKey(key) || packagedGerman.containsKey(key),
                    schema == null ? null : schema.name(),
                    schema == null ? null : schema.description(),
                    schema == null ? List.of() : schema.args(),
                    schema == null ? List.of() : schema.section(),
                    schema == null ? null : schema.format(),
                    schema == null ? null : schema.shown()));
        }
        return new MessageBundle(location.service(), location.module(), location.writable(), entries);
    }

    private record SchemaEntry(String key, String name, String description, List<MessageArg> args,
                               List<String> section, String format, String shown) {
    }

    /**
     * One {@code schema.json}. A file this process cannot make sense of is logged and read as empty:
     * the texts are still worth showing without their names, and refusing the whole bundle over it
     * would hide the texts as well.
     */
    private static List<SchemaEntry> readSchema(final MessageBundleLocation location, final String name,
                                                final InputStream in) throws IOException {
        final String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        try {
            final JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            final Map<String, List<String>> properties = contextProperties(root);
            // The globals, expanded once: every message of the network may use them, none has to.
            final List<MessageArg> globals = new ArrayList<>();
            final JsonArray declaredGlobals = root.getAsJsonArray("globals");
            if (declaredGlobals != null) {
                for (final JsonElement global : declaredGlobals) {
                    final JsonObject object = global.getAsJsonObject();
                    expand(object.get("name").getAsString(), object.get("context").getAsString(), properties,
                            true, globals);
                }
            }
            final List<SchemaEntry> answer = new ArrayList<>();
            for (final JsonElement element : root.getAsJsonArray("messages")) {
                final JsonObject message = element.getAsJsonObject();
                final List<MessageArg> args = new ArrayList<>();
                final Set<String> roles = new HashSet<>();
                for (final JsonElement arg : message.getAsJsonArray("args")) {
                    final JsonObject object = arg.getAsJsonObject();
                    final String argName = object.get("name").getAsString();
                    final String context = stringOf(object, "context");
                    if (context == null) {
                        args.add(new MessageArg(argName, object.get("component").getAsBoolean()));
                    } else {
                        roles.add(argName);
                        expand(argName, context, properties, false, args);
                    }
                }
                // A message that names a global's role itself (a hand-over naming its own server)
                // has filled it; the global would only say the same thing twice.
                for (final MessageArg global : globals) {
                    if (!roles.contains(global.name().substring(0, global.name().indexOf('.')))) {
                        args.add(global);
                    }
                }
                final List<String> section = new ArrayList<>();
                for (final JsonElement part : message.getAsJsonArray("section")) {
                    section.add(part.isJsonNull() ? null : part.getAsString());
                }
                answer.add(new SchemaEntry(message.get("key").getAsString(), stringOf(message, "name"),
                        stringOf(message, "description"), args, section, stringOf(message, "format"),
                        stringOf(message, "shown")));
            }
            return answer;
        } catch (final JsonParseException | IllegalStateException | NullPointerException
                       | UnsupportedOperationException e) {
            LOG.warn("{}: {} is not a message schema this worker can read, so its names are left out: {}",
                    location.jar(), name, e.toString());
            return List.of();
        }
    }

    /** Each context type's properties, by type; empty for a schema written before types existed. */
    private static Map<String, List<String>> contextProperties(final JsonObject root) {
        final Map<String, List<String>> answer = new HashMap<>();
        final JsonObject contexts = root.getAsJsonObject("contexts");
        if (contexts == null) {
            return answer;
        }
        for (final Map.Entry<String, JsonElement> type : contexts.entrySet()) {
            final List<String> names = new ArrayList<>();
            type.getValue().getAsJsonObject().getAsJsonArray("properties")
                    .forEach(property -> names.add(property.getAsString()));
            answer.put(type.getKey(), names);
        }
        return answer;
    }

    /** One placeholder per property of {@code type}, {@code role.property}, into {@code into}. */
    private static void expand(final String role, final String type, final Map<String, List<String>> properties,
                               final boolean global, final List<MessageArg> into) {
        final List<String> names = properties.get(type);
        if (names == null) {
            throw new IllegalStateException("the role " + role + " has the type " + type
                    + ", which the schema does not describe");
        }
        for (final String property : names) {
            into.add(new MessageArg(role + "." + property, false, type, global));
        }
    }

    private static @Nullable String stringOf(final JsonObject object, final String field) {
        final JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    /** {@code <directory>/<language>.properties}, or an empty map when there is no override yet. */
    private static Map<String, String> readOverride(final Path directory, final String language)
            throws IOException {
        final Path file = directory.resolve(language + ".properties");
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try (InputStream in = Files.newInputStream(file)) {
            return readProperties(in);
        }
    }

    /**
     * A {@code .properties} stream, read as UTF-8.
     *
     * <p>Read through a {@link Reader} rather than {@code Properties.load(InputStream)}: the
     * {@code InputStream} overload treats the bytes as ISO-8859-1 and expects a non-ASCII character
     * spelled out as {@code \\uXXXX}, which is not how these bundles are written (they are UTF-8, and
     * an umlaut is a literal umlaut) - see {@code Messages#read} for the same reasoning on the plugin
     * side, and {@link #escapeValue} for the write side of the same round trip.</p>
     */
    private static Map<String, String> readProperties(final InputStream in) throws IOException {
        final Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        final Map<String, String> map = new HashMap<>(properties.size());
        properties.forEach((key, value) -> map.put(String.valueOf(key), String.valueOf(value)));
        return map;
    }

    // -----------------------------------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------------------------------

    /**
     * Applies changes to one language's override file, creating or updating it.
     *
     * <p>A read-modify-write of the whole file, unlike {@link ConfigFiles#write}: an override file
     * carries no comments and no key order an operator relies on (steward/50's concern about
     * clobbering hand-written documentation does not apply here - the file exists purely because
     * this interface, or an operator copying its {@code README.txt}, wrote a key into it), so there
     * is nothing lost by rewriting it whole and sorted.</p>
     *
     * @param location the bundle
     * @param language {@code "en"} or {@code "de"}
     * @param changes  key to new value; a {@code null} value <b>removes</b> the key from the
     *                 override rather than writing an empty string - resetting a line is not the
     *                 same as blanking it (steward/48)
     * @throws IllegalArgumentException if {@code language} is anything but {@code "en"} or {@code "de"}
     * @throws IOException              if the directory or the file cannot be written
     */
    public static void write(final @NotNull MessageBundleLocation location, final @NotNull String language,
                             final @NotNull Map<String, String> changes) throws IOException {
        if (!"en".equals(language) && !"de".equals(language)) {
            throw new IllegalArgumentException(
                    "language has to be \"en\" or \"de\", not \"" + language + "\"");
        }
        Files.createDirectories(location.overrideDirectory());
        final Path file = location.overrideDirectory().resolve(language + ".properties");
        final Map<String, String> content = new TreeMap<>(readOverride(location.overrideDirectory(), language));
        changes.forEach((key, value) -> {
            if (value == null) {
                content.remove(key);
            } else {
                content.put(key, value);
            }
        });
        writeAtomically(file, content);
    }

    private static void writeAtomically(final Path file, final Map<String, String> sortedContent)
            throws IOException {
        final StringBuilder text = new StringBuilder();
        for (final Map.Entry<String, String> entry : sortedContent.entrySet()) {
            text.append(escapeKey(entry.getKey())).append('=').append(escapeValue(entry.getValue()))
                    .append('\n');
        }

        final Path directory = file.toAbsolutePath().getParent();
        Path temp = null;
        try {
            temp = Files.createTempFile(directory, ".", ".tmp");
            Files.writeString(temp, text.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } finally {
            if (temp != null) {
                Files.deleteIfExists(temp);
            }
        }
    }

    /**
     * Escapes a key for the {@code .properties} format: a plain-text writer's counterpart to
     * {@link Properties#load(Reader)}, which is what reads this back.
     */
    private static String escapeKey(final String key) {
        final StringBuilder out = new StringBuilder(key.length() + 8);
        for (int i = 0; i < key.length(); i++) {
            final char c = key.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case ' ' -> out.append("\\ ");
                case ':' -> out.append("\\:");
                case '=' -> out.append("\\=");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Escapes a value: a backslash, a real newline, carriage return or tab, and any leading space
     * (which {@link Properties#load(Reader)} would otherwise trim as insignificant whitespace).
     * {@code :} and {@code =} need no escaping here - the parser only treats them specially before
     * the first unescaped separator, which is the key {@link #escapeKey} has already produced.
     *
     * <p><b>Never {@code \\uXXXX}.</b> That escaping belongs to {@code Properties.store(OutputStream,
     * ...)}, which assumes ISO-8859-1; writing straight UTF-8 characters through a UTF-8
     * {@link Files#writeString} and reading them back through {@link #readProperties} is the same
     * round trip {@code Messages} itself relies on, and it is what keeps "Mühle" from becoming
     * "MÃ¼hle" (steward/48).</p>
     */
    private static String escapeValue(final String value) {
        final StringBuilder out = new StringBuilder(value.length() + 8);
        boolean leading = true;
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == ' ' && leading) {
                out.append("\\ ");
                continue;
            }
            leading = false;
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    // -----------------------------------------------------------------------------------------
    // Placeholders
    // -----------------------------------------------------------------------------------------

    /**
     * The placeholders {@code edited} uses that {@code entry}'s schema does not declare - each
     * once, in the order they appear. Such a text cannot be filled: the plugin substitutes the
     * declared arguments and nothing else, so an unknown {@code {name}} would draw literally.
     *
     * <p>Only {@code {name}} and the underscored {@code <_name>} tags are checked. An ordinary
     * MiniMessage tag such as {@code <bold>} is formatting, not a placeholder, and a Component
     * argument's own tag ({@code <player>}) is indistinguishable from one without a list of every
     * tag Adventure knows. An entry the schema does not describe is never checked.</p>
     */
    public static @NotNull List<String> unknownPlaceholders(final @NotNull MessageEntry entry,
                                                             final @Nullable String edited) {
        if (!entry.described() || edited == null || edited.isEmpty()) {
            return List.of();
        }
        final Set<String> declared = new HashSet<>();
        for (final MessageArg arg : entry.args()) {
            declared.add(arg.token());
        }
        final List<String> unknown = new ArrayList<>();
        final Matcher matcher = DECLARABLE.matcher(edited);
        while (matcher.find()) {
            final String token = matcher.group();
            if (!declared.contains(token) && !unknown.contains(token)) {
                unknown.add(token);
            }
        }
        return unknown;
    }

    /** Every placeholder token in {@code text}, in the order it appears; {@code null} reads as none. */
    public static @NotNull List<String> placeholdersOf(final @Nullable String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        final List<String> found = new ArrayList<>();
        final Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    /**
     * The placeholders {@code original} names that {@code edited} no longer does - never the other
     * way round, and never a rejection. A new placeholder is somebody's choice; a lost one is worth a
     * warning on save, the same rule steward/60 gives a syntax error in the raw editor.
     *
     * @param original the packaged text the operator started from - see {@link MessageBundles} for
     *                 why that is the jar's text and not a previous override
     * @param edited   what is about to be saved
     * @return the missing tokens, each once, in the order {@code original} has them; empty if none
     *         are missing or {@code original} names none at all
     */
    public static @NotNull List<String> missingPlaceholders(final @Nullable String original,
                                                             final @Nullable String edited) {
        final List<String> before = placeholdersOf(original);
        if (before.isEmpty()) {
            return List.of();
        }
        final Set<String> after = new HashSet<>(placeholdersOf(edited));
        final List<String> missing = new ArrayList<>();
        for (final String token : before) {
            if (!after.contains(token) && !missing.contains(token)) {
                missing.add(token);
            }
        }
        return missing;
    }
}
