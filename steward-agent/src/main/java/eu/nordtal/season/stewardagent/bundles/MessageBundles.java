package eu.nordtal.season.stewardagent.bundles;

import com.google.gson.JsonObject;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.internalapi.agent.JarName;
import eu.nordtal.season.internalapi.agent.MessageArg;
import eu.nordtal.season.internalapi.agent.MessageBundle;
import eu.nordtal.season.internalapi.agent.MessageEntry;
import eu.nordtal.season.messages.MessageJson;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.PackagedTexts;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.messages.spec.MessageSchema;
import eu.nordtal.season.messages.text.Declaration;
import eu.nordtal.season.messages.value.Kind;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the message bundles that a module ships in its jar, for the editor.
 *
 * One jar packages several bundles, its own and those of the shared modules shaded into it.
 */
public final class MessageBundles {

    private static final Logger LOG = LoggerFactory.getLogger(MessageBundles.class);

    private static final Pattern BUNDLE_ENTRY =
            Pattern.compile("messages/([^/]+)/([a-z]{2,3}(?:-[a-z0-9]+)*)\\.properties");

    /** The schema a root's message spec writes into the jar at build time. */
    private static final Pattern SCHEMA_ENTRY = Pattern.compile("messages/([^/]+)/schema\\.json");

    /** The declarations a module's documents write into the jar at build time, one file per namespace. */
    private static final Pattern DOCUMENTS_ENTRY = Pattern.compile("documents/([^/]+)/declarations\\.json");

    private MessageBundles() {}

    /**
     * Returns the bundles of every jar whose process follows the message overrides.
     *
     * @param jars every jar of ours on every service, as the descriptors find them
     * @return every bundle found, by service then module; a plugin's module is its jar's name, a service's own is empty
     */
    public static List<MessageBundleLocation> discover(final List<ServiceJar> jars) {
        final List<MessageBundleLocation> found = new ArrayList<>();
        for (final ServiceJar jar : jars) {
            final String module = jar.ownImage()
                    ? ""
                    : JarName.prefixOf(jar.path().getFileName().toString());
            if (jar.followsMessages() && module != null && packagesBundles(jar.path())) {
                found.add(new MessageBundleLocation(jar.service(), module, jar.path()));
            }
        }
        found.sort(Comparator.comparing(MessageBundleLocation::service).thenComparing(MessageBundleLocation::module));
        return found;
    }

    /** Whether a jar carries a packaged bundle or documents; one that cannot be opened is left out with a warning. */
    private static boolean packagesBundles(final Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.stream()
                    .anyMatch(entry -> BUNDLE_ENTRY.matcher(entry.getName()).matches()
                            || DOCUMENTS_ENTRY.matcher(entry.getName()).matches());
        } catch (final IOException e) {
            LOG.warn("{} cannot be opened to look for message bundles: {}", jar, e.toString());
            return false;
        }
    }

    /**
     * Opens {@code location}'s jar and reads every bundle it packages into one form.
     *
     * @return the bundle, each bundle's schema keys in its order, then the rest sorted, and every document
     * @throws IOException if the jar cannot be read
     * @throws IllegalStateException if a packaged file numbers its variants with a gap
     */
    public static MessageBundle read(final MessageBundleLocation location) throws IOException {
        final Packaged packaged = readPackaged(location);
        final Map<String, SchemaEntry> described = new LinkedHashMap<>();
        final Map<String, String> bundleOf = new HashMap<>();
        packaged.schemas()
                .forEach((bundle, list) -> list.forEach(entry -> {
                    described.putIfAbsent(entry.key(), entry);
                    bundleOf.putIfAbsent(entry.key(), bundle);
                }));
        final Set<String> undescribed = new TreeSet<>();
        packaged.texts()
                .forEach((bundle, languages) -> languages
                        .values()
                        .forEach(texts -> texts.keySet().forEach(key -> {
                            bundleOf.putIfAbsent(key, bundle);
                            if (!described.containsKey(key)) {
                                undescribed.add(key);
                            }
                        })));
        final List<String> keys = new ArrayList<>(described.keySet());
        keys.addAll(undescribed);
        final List<MessageEntry> entries = new ArrayList<>(keys.size());
        for (final String key : keys) {
            // A described key without a text in the jar belongs to the bundle whose schema names it.
            final String bundle = java.util.Objects.requireNonNull(bundleOf.get(key), key);
            final Map<String, List<String>> texts = new HashMap<>();
            packaged.texts().getOrDefault(bundle, Map.of()).forEach((language, byKey) -> {
                final List<String> variants = byKey.get(key);
                if (variants != null) {
                    texts.put(language, variants);
                }
            });
            entries.add(entryOf(key, bundle, texts, described.get(key), packaged));
        }
        return new MessageBundle(location.service(), location.module(), entries, packaged.documents());
    }

    private static MessageEntry entryOf(
            final String key,
            final String bundle,
            final Map<String, List<String>> texts,
            final @Nullable SchemaEntry schema,
            final Packaged packaged) {
        return new MessageEntry(
                key,
                bundle,
                texts,
                Map.of(),
                !texts.isEmpty(),
                schema == null ? null : schema.name(),
                schema == null ? null : schema.description(),
                schema == null
                        ? List.of()
                        : schema.args().stream()
                                .map(arg -> withExample(arg, packaged))
                                .toList(),
                schema == null ? List.of() : schema.section(),
                schema == null ? null : schema.format(),
                schema == null ? List.of() : schema.shown(),
                schema == null ? 0 : schema.limit());
    }

    /**
     * The example read as its kind and, for a value that is a message, its words in every language of the jar.
     *
     * The editor previews a nested message as a player reads it; the server, which renders the key, is sent the key.
     */
    private static MessageArg withExample(final MessageArg arg, final Packaged packaged) {
        final String example = arg.example();
        final Kind kind = arg.kind() == null ? null : Kind.byToken(arg.kind()).orElse(null);
        if (example == null || kind == null) {
            return arg;
        }
        final Object value;
        try {
            value = kind.example(example);
        } catch (final IllegalArgumentException | DateTimeException unread) {
            return arg;
        }
        final Map<String, String> words = new TreeMap<>();
        if (value instanceof final MessageRef message) {
            final int slash = example.indexOf('/');
            final String bundle = slash < 0 ? null : example.substring(0, slash).strip();
            packaged.texts().forEach((name, languages) -> {
                if (bundle != null && !bundle.equals(name)) {
                    return;
                }
                languages.forEach((language, byKey) -> {
                    final List<String> variants = byKey.get(message.key());
                    if (variants != null && !variants.isEmpty()) {
                        words.putIfAbsent(language, variants.getFirst());
                    }
                });
            });
        }
        return arg.withExample(words, MessageJson.typed(value));
    }

    /**
     * Every packaged text and schema of one jar.
     *
     * @param texts bundle to language to key to variants
     * @param schemas bundle to described keys, sorted by bundle so the order does not follow the jar
     * @param documents every document as {@code {declaration, document}}, by namespace and name
     */
    private record Packaged(
            Map<String, Map<String, Map<String, List<String>>>> texts,
            Map<String, List<SchemaEntry>> schemas,
            List<JsonObject> documents) {}

    private static Packaged readPackaged(final MessageBundleLocation location) throws IOException {
        final Map<String, Map<String, Map<String, List<String>>>> texts = new TreeMap<>();
        final Map<String, List<SchemaEntry>> schemas = new TreeMap<>();
        final Set<String> namespaces = new TreeSet<>();
        try (ZipFile jar = new ZipFile(location.jar().toFile())) {
            final Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                final Matcher schema = SCHEMA_ENTRY.matcher(entry.getName());
                final Matcher documents = DOCUMENTS_ENTRY.matcher(entry.getName());
                final Matcher bundle = BUNDLE_ENTRY.matcher(entry.getName());
                if (schema.matches()) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        schemas.put(schema.group(1), readSchema(location, entry.getName(), in));
                    }
                } else if (documents.matches()) {
                    namespaces.add(documents.group(1));
                } else if (bundle.matches()) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        texts.computeIfAbsent(bundle.group(1), ignored -> new HashMap<>())
                                .put(bundle.group(2), PackagedTexts.read(in, entry.getName()));
                    }
                }
            }
        }
        return new Packaged(texts, schemas, readDocuments(location, namespaces));
    }

    /** Every document of {@code namespaces} as its tree; a namespace steward cannot read is logged and left out. */
    private static List<JsonObject> readDocuments(final MessageBundleLocation location, final Set<String> namespaces)
            throws IOException {
        final List<JsonObject> read = new ArrayList<>();
        try (URLClassLoader jar =
                new URLClassLoader(new URL[] {location.jar().toUri().toURL()}, null)) {
            for (final String namespace : namespaces) {
                try {
                    Documents.packaged(jar, namespace).values().forEach(packaged -> {
                        final JsonObject tree = new JsonObject();
                        tree.add("declaration", Json.gson().toJsonTree(packaged.declaration()));
                        tree.add("document", Json.tree(packaged.document().json()));
                        read.add(tree);
                    });
                } catch (final RuntimeException e) {
                    LOG.warn(
                            "{}: the documents of {} are not readable, so they are left out: {}",
                            location.jar(),
                            namespace,
                            e.toString());
                }
            }
        }
        return read;
    }

    private record SchemaEntry(
            String key,
            @Nullable String name,
            @Nullable String description,
            List<MessageArg> args,
            List<String> section,
            @Nullable String format,
            List<String> shown,
            int limit) {}

    /** One {@code schema.json}; one that cannot be parsed is logged and read as empty, so the texts still show. */
    private static List<SchemaEntry> readSchema(
            final MessageBundleLocation location, final String name, final InputStream in) throws IOException {
        try {
            final MessageSchema.Bundle schema = MessageSchema.decode(
                    new InputStreamReader(new ByteArrayInputStream(in.readAllBytes()), StandardCharsets.UTF_8));
            final List<SchemaEntry> answer = new ArrayList<>();
            for (final MessageSchema.Entry entry : schema.messages()) {
                answer.add(schemaEntryOf(schema, entry));
            }
            return answer;
        } catch (final RuntimeException e) {
            LOG.warn(
                    "{}: {} is not a message schema steward can read, so its names are left out: {}",
                    location.jar(),
                    name,
                    e.toString());
            return List.of();
        }
    }

    /**
     * Returns the placeholders of one message as the editor offers them, from the {@link Declaration} of the validator.
     */
    private static SchemaEntry schemaEntryOf(final MessageSchema.Bundle schema, final MessageSchema.Entry entry) {
        final Declaration declaration = schema.declaration(entry);
        final Map<String, String> typeOfRole = new HashMap<>();
        entry.args().stream()
                .filter(arg -> arg.name() != null && arg.context() != null)
                .forEach(arg -> typeOfRole.put(arg.name(), arg.context()));
        schema.globals().stream()
                .filter(global -> !typeOfRole.containsKey(global.name()))
                .forEach(global -> typeOfRole.put(global.name(), global.context()));
        final List<MessageArg> args = new ArrayList<>();
        for (final MessageSchema.Arg arg : entry.args()) {
            final String argName = arg.name();
            if (argName == null) {
                continue;
            }
            if (arg.action()) {
                args.add(new MessageArg(argName, null, null, false, null, true));
            } else if (arg.context() == null) {
                args.add(new MessageArg(argName, arg.kind(), null, false, arg.example(), false));
            } else {
                attributes(argName, declaration, typeOfRole, false, args);
            }
        }
        for (final MessageSchema.GlobalJson global : schema.globals()) {
            if (entry.args().stream().noneMatch(arg -> global.name().equals(arg.name()))) {
                attributes(global.name(), declaration, typeOfRole, true, args);
            }
        }
        return new SchemaEntry(
                entry.key(),
                entry.name(),
                entry.description(),
                args,
                entry.section(),
                entry.format().name(),
                entry.shown().stream().map(Enum::name).toList(),
                declaration.limit());
    }

    private static void attributes(
            final String role,
            final Declaration declaration,
            final Map<String, String> typeOfRole,
            final boolean global,
            final List<MessageArg> into) {
        declaration.values().entrySet().stream()
                .filter(value -> value.getKey().startsWith(role + "."))
                .sorted(Map.Entry.comparingByKey())
                .forEach(value -> into.add(new MessageArg(
                        value.getKey(),
                        value.getValue().token(),
                        typeOfRole.get(role),
                        global,
                        declaration.examples().get(value.getKey()),
                        false)));
    }
}
