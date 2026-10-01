package eu.nordtal.s2.steward.configfile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.steward.plan.JarName;
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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the message bundles a module ships in its jar, merged with what an operator has overridden on disk.
 *
 * The jar holds the packaged text; overrides merge per key across every root, as {@code Messages} does.
 */
public final class MessageBundles {

    private static final Logger LOG = LoggerFactory.getLogger(MessageBundles.class);

    private static final Pattern BUNDLE_ENTRY = Pattern.compile("messages/([^/]+)/(en|de)\\.properties");

    /** The schema a root's message spec writes into the jar at build time. */
    private static final Pattern SCHEMA_ENTRY = Pattern.compile("messages/([^/]+)/schema\\.json");

    /** A placeholder as a spec declares it: {@code {name}} for text, {@code <_name>} for a legacy tag. */
    private static final Pattern DECLARABLE = Pattern.compile("\\{([A-Za-z0-9_.-]+)}|<(_[A-Za-z0-9_-]+)>");

    /** A placeholder in either form; plain MiniMessage formatting tags such as {@code <bold>} are not matched. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[A-Za-z0-9_.-]+}|<_[A-Za-z0-9_-]+>");

    /** The directory a module's saved translations live in, which {@link ConfigFiles#discover} skips. */
    static final String DIRECTORY = "messages";

    private MessageBundles() {}

    /**
     * Every message bundle under the configs mount; one whose jar is not there yet is left out with a warning.
     *
     * @param configsRoot the configs mount
     * @param volumesRoot the volumes mount, where a standalone jar lives; {@code null} to search the configs mount only
     * @return every bundle found, by service then module; empty if {@code configsRoot} does not exist
     */
    public static List<MessageBundleLocation> discover(final Path configsRoot, final @Nullable Path volumesRoot) {
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

    private static @Nullable MessageBundleLocation locationOf(
            final Path configsRoot, final @Nullable Path volumesRoot, final Path messagesDirectory) {
        final Path relative = configsRoot.relativize(messagesDirectory);
        if (relative.getNameCount() < 2) {
            // A messages directory at the mount's root has no service directory to search a jar under.
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
            LOG.warn(
                    "{}: no jar named like \"{}\" under {}{} - this bundle cannot be shown yet",
                    messagesDirectory,
                    prefix,
                    configsRoot.resolve(service),
                    volumesRoot == null ? "" : " or " + volumesRoot.resolve(service));
            return null;
        }
        return new MessageBundleLocation(
                service, module.toString(), jar, messagesDirectory, Files.isWritable(messagesDirectory));
    }

    private static @Nullable Path findJar(
            final Path configsRoot, final @Nullable Path volumesRoot, final String service, final String prefix) {
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

    /**
     * Opens {@code location}'s jar and override directory and merges them into one bundle.
     *
     * @param location where to read from
     * @return the bundle, the schema's keys in its order, then the rest sorted
     * @throws IOException if the jar or an override file cannot be read
     */
    public static MessageBundle read(final MessageBundleLocation location) throws IOException {
        final Packaged packaged = readPackaged(location);
        final Map<String, SchemaEntry> described = new LinkedHashMap<>();
        packaged.schemas().values().forEach(list -> list.forEach(entry -> described.putIfAbsent(entry.key(), entry)));

        final Map<String, String> overrideEnglish = readOverride(location.overrideDirectory(), "en");
        final Map<String, String> overrideGerman = readOverride(location.overrideDirectory(), "de");
        final List<MessageEntry> entries = entriesOf(packaged, described, overrideEnglish, overrideGerman);
        return new MessageBundle(location.service(), location.module(), location.writable(), entries);
    }

    private record Packaged(
            Map<String, String> english, Map<String, String> german, Map<String, List<SchemaEntry>> schemas) {}

    /** Opens the jar once and reads every packaged bundle and schema it carries. */
    private static Packaged readPackaged(final MessageBundleLocation location) throws IOException {
        final Map<String, String> packagedEnglish = new HashMap<>();
        final Map<String, String> packagedGerman = new HashMap<>();
        // Sorted, so entry order does not depend on the jar's directory order.
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
                final Map<String, String> target = "en".equals(matcher.group(2)) ? packagedEnglish : packagedGerman;
                try (InputStream in = jar.getInputStream(entry)) {
                    target.putAll(readProperties(in));
                }
            }
        }
        return new Packaged(packagedEnglish, packagedGerman, schemas);
    }

    /** The schema's order first, then everything it does not describe, sorted; one {@link MessageEntry} per key. */
    private static List<MessageEntry> entriesOf(
            final Packaged packaged,
            final Map<String, SchemaEntry> described,
            final Map<String, String> overrideEnglish,
            final Map<String, String> overrideGerman) {
        final Set<String> undescribed = new TreeSet<>();
        undescribed.addAll(packaged.english().keySet());
        undescribed.addAll(packaged.german().keySet());
        undescribed.addAll(overrideEnglish.keySet());
        undescribed.addAll(overrideGerman.keySet());
        undescribed.removeAll(described.keySet());
        final List<String> keys = new ArrayList<>(described.keySet());
        keys.addAll(undescribed);

        final List<MessageEntry> entries = new ArrayList<>(keys.size());
        for (final String key : keys) {
            final SchemaEntry schema = described.get(key);
            entries.add(new MessageEntry(
                    key,
                    packaged.english().get(key),
                    packaged.german().get(key),
                    overrideEnglish.get(key),
                    overrideGerman.get(key),
                    packaged.english().containsKey(key) || packaged.german().containsKey(key),
                    schema == null ? null : schema.name(),
                    schema == null ? null : schema.description(),
                    schema == null ? List.of() : schema.args(),
                    schema == null ? List.of() : schema.section(),
                    schema == null ? null : schema.format(),
                    schema == null ? null : schema.shown()));
        }
        return entries;
    }

    private record SchemaEntry(
            String key,
            @Nullable String name,
            @Nullable String description,
            List<MessageArg> args,
            List<String> section,
            @Nullable String format,
            @Nullable String shown) {}

    /** One {@code schema.json}; one that cannot be parsed is logged and read as empty, so the texts still show. */
    private static List<SchemaEntry> readSchema(
            final MessageBundleLocation location, final String name, final InputStream in) throws IOException {
        final String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        try {
            final JsonObject root = Json.tree(text).getAsJsonObject();
            final Map<String, List<String>> properties = contextProperties(root);
            final List<MessageArg> globals = globalsOf(root, properties);
            final List<SchemaEntry> answer = new ArrayList<>();
            for (final JsonElement element : root.getAsJsonArray("messages")) {
                answer.add(schemaEntryOf(element.getAsJsonObject(), properties, globals));
            }
            return answer;
        } catch (final JsonParseException
                | IllegalStateException
                | NullPointerException
                | UnsupportedOperationException e) {
            LOG.warn(
                    "{}: {} is not a message schema steward can read, so its names are left out: {}",
                    location.jar(),
                    name,
                    e.toString());
            return List.of();
        }
    }

    /** The globals, expanded once: every message may use them, none has to. */
    private static List<MessageArg> globalsOf(final JsonObject root, final Map<String, List<String>> properties) {
        final List<MessageArg> globals = new ArrayList<>();
        final JsonArray declaredGlobals = root.getAsJsonArray("globals");
        if (declaredGlobals != null) {
            for (final JsonElement global : declaredGlobals) {
                final JsonObject object = global.getAsJsonObject();
                expand(
                        object.get("name").getAsString(),
                        object.get("context").getAsString(),
                        properties,
                        true,
                        globals);
            }
        }
        return globals;
    }

    private static SchemaEntry schemaEntryOf(
            final JsonObject message, final Map<String, List<String>> properties, final List<MessageArg> globals) {
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
        // A message naming the global's role has filled it already.
        for (final MessageArg global : globals) {
            if (!roles.contains(global.name().substring(0, global.name().indexOf('.')))) {
                args.add(global);
            }
        }
        final List<String> section = new ArrayList<>();
        for (final JsonElement part : message.getAsJsonArray("section")) {
            section.add(part.isJsonNull() ? null : part.getAsString());
        }
        return new SchemaEntry(
                message.get("key").getAsString(),
                stringOf(message, "name"),
                stringOf(message, "description"),
                args,
                section,
                stringOf(message, "format"),
                stringOf(message, "shown"));
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
            type.getValue()
                    .getAsJsonObject()
                    .getAsJsonArray("properties")
                    .forEach(property -> names.add(property.getAsString()));
            answer.put(type.getKey(), names);
        }
        return answer;
    }

    /** One placeholder per property of {@code type}, {@code role.property}, into {@code into}. */
    private static void expand(
            final String role,
            final String type,
            final Map<String, List<String>> properties,
            final boolean global,
            final List<MessageArg> into) {
        final List<String> names = properties.get(type);
        if (names == null) {
            throw new IllegalStateException(
                    "the role " + role + " has the type " + type + ", which the schema does not describe");
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
    private static Map<String, String> readOverride(final Path directory, final String language) throws IOException {
        final Path file = directory.resolve(language + ".properties");
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try (InputStream in = Files.newInputStream(file)) {
            return readProperties(in);
        }
    }

    /** A {@code .properties} stream, read as UTF-8 through a {@link Reader} so an umlaut stays literal. */
    private static Map<String, String> readProperties(final InputStream in) throws IOException {
        final Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        final Map<String, String> map = new HashMap<>(properties.size());
        properties.forEach((key, value) -> map.put(String.valueOf(key), String.valueOf(value)));
        return map;
    }

    /**
     * Writes changes to one language's override file, rewriting it whole and sorted since it holds no comments.
     *
     * @param location the bundle
     * @param language {@code "en"} or {@code "de"}
     * @param changes key to new value; a {@code null} value removes the key rather than writing an empty string
     * @throws IllegalArgumentException if {@code language} is anything but {@code "en"} or {@code "de"}
     * @throws IOException if the directory or the file cannot be written
     */
    public static void write(
            final MessageBundleLocation location, final String language, final Map<String, String> changes)
            throws IOException {
        if (!"en".equals(language) && !"de".equals(language)) {
            throw new IllegalArgumentException("language has to be \"en\" or \"de\", not \"" + language + "\"");
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

    private static void writeAtomically(final Path file, final Map<String, String> sortedContent) throws IOException {
        final StringBuilder text = new StringBuilder();
        for (final Map.Entry<String, String> entry : sortedContent.entrySet()) {
            text.append(escapeKey(entry.getKey()))
                    .append('=')
                    .append(escapeValue(entry.getValue()))
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

    /** Escapes a key for the {@code .properties} format that {@link Properties#load(Reader)} reads back. */
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
     * Escapes a backslash, a newline, a carriage return, a tab and a leading space, and nothing else.
     *
     * Never {@code \\uXXXX}: the file is written and read as UTF-8, which keeps "Mühle" from becoming "MÃ¼hle".
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

    /**
     * The placeholders {@code edited} uses that {@code entry}'s schema does not declare, each once, in order.
     *
     * Only {@code {name}} and {@code <_name>} are checked, and an entry the schema does not describe never is.
     */
    public static List<String> unknownPlaceholders(final MessageEntry entry, final @Nullable String edited) {
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
    public static List<String> placeholdersOf(final @Nullable String text) {
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
     * The placeholders {@code original} names that {@code edited} no longer does, for a warning on save.
     *
     * @param original the packaged text the operator started from
     * @param edited what is about to be saved
     * @return the missing tokens, each once, in {@code original}'s order; empty if none are missing
     */
    public static List<String> missingPlaceholders(final @Nullable String original, final @Nullable String edited) {
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
