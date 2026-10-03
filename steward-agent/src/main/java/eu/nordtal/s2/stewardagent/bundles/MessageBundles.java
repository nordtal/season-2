package eu.nordtal.s2.stewardagent.bundles;

import eu.nordtal.s2.internalapi.agent.JarName;
import eu.nordtal.s2.internalapi.agent.MessageArg;
import eu.nordtal.s2.internalapi.agent.MessageBundle;
import eu.nordtal.s2.internalapi.agent.MessageEntry;
import eu.nordtal.s2.messages.PackagedTexts;
import eu.nordtal.s2.messages.spec.MessageSchema;
import eu.nordtal.s2.messages.text.Declaration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Reads the message bundles a module ships in its jar, for the editor; an admin's overrides are rows in the database.
 *
 * One jar packages several bundles, its own and those of the shared modules shaded into it.
 */
public final class MessageBundles {

    private static final Logger LOG = LoggerFactory.getLogger(MessageBundles.class);

    private static final Pattern BUNDLE_ENTRY = Pattern.compile("messages/([^/]+)/(en|de)\\.properties");

    /** The schema a root's message spec writes into the jar at build time. */
    private static final Pattern SCHEMA_ENTRY = Pattern.compile("messages/([^/]+)/schema\\.json");

    private MessageBundles() {}

    /**
     * Every jar that packages message bundles: a plugin's in a service's plugins folder, or the service's own.
     *
     * @param configsRoot the configs mount, one directory per service
     * @param images where the jar of a service with no plugins folder is found: the bot's, in its image
     * @return every bundle found, by service then module; empty if {@code configsRoot} does not exist
     */
    public static List<MessageBundleLocation> discover(final Path configsRoot, final ImageJars images) {
        if (!Files.isDirectory(configsRoot)) {
            return List.of();
        }
        final List<MessageBundleLocation> found = new ArrayList<>();
        try (DirectoryStream<Path> services = Files.newDirectoryStream(configsRoot, Files::isDirectory)) {
            for (final Path directory : services) {
                found.addAll(locationsIn(directory, images));
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot list the message bundles under " + configsRoot, e);
        }
        found.sort(Comparator.comparing(MessageBundleLocation::service).thenComparing(MessageBundleLocation::module));
        return found;
    }

    /** The jars directly in one service's directory that package bundles, else the service's own from its image. */
    private static List<MessageBundleLocation> locationsIn(final Path directory, final ImageJars images)
            throws IOException {
        final String service = directory.getFileName().toString();
        final List<MessageBundleLocation> found = new ArrayList<>();
        boolean anyJar = false;
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(directory, "*.jar")) {
            for (final Path jar : jars) {
                anyJar = true;
                final String prefix = JarName.prefixOf(jar.getFileName().toString());
                if (prefix != null && Files.isRegularFile(jar) && packagesBundles(jar)) {
                    found.add(new MessageBundleLocation(service, prefix, jar));
                }
            }
        }
        if (!anyJar) {
            final Path own = images.jarOf(service);
            if (own != null && packagesBundles(own)) {
                found.add(new MessageBundleLocation(service, "", own));
            }
        }
        return found;
    }

    /** Whether a jar carries a packaged bundle; one that cannot be opened is left out with a warning. */
    private static boolean packagesBundles(final Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.stream()
                    .anyMatch(entry -> BUNDLE_ENTRY.matcher(entry.getName()).matches());
        } catch (final IOException e) {
            LOG.warn("{} cannot be opened to look for message bundles: {}", jar, e.toString());
            return false;
        }
    }

    /**
     * Opens {@code location}'s jar and reads every bundle it packages into one form.
     *
     * @return the bundle, each bundle's schema keys in its order, then the rest sorted
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
            // A described key the jar has no text for still belongs to the bundle whose schema names it.
            final String bundle = java.util.Objects.requireNonNull(bundleOf.get(key), key);
            final Map<String, Map<String, List<String>>> languages =
                    packaged.texts().getOrDefault(bundle, Map.of());
            final List<String> english = languages.getOrDefault("en", Map.of()).get(key);
            final List<String> german = languages.getOrDefault("de", Map.of()).get(key);
            entries.add(entryOf(key, bundle, english, german, described.get(key)));
        }
        return new MessageBundle(location.service(), location.module(), entries);
    }

    private static MessageEntry entryOf(
            final String key,
            final String bundle,
            final @Nullable List<String> english,
            final @Nullable List<String> german,
            final @Nullable SchemaEntry schema) {
        return new MessageEntry(
                key,
                bundle,
                english == null ? null : english.getFirst(),
                german == null ? null : german.getFirst(),
                english == null ? List.of() : english,
                german == null ? List.of() : german,
                null,
                null,
                english != null || german != null,
                schema == null ? null : schema.name(),
                schema == null ? null : schema.description(),
                schema == null ? List.of() : schema.args(),
                schema == null ? List.of() : schema.section(),
                schema == null ? null : schema.format(),
                schema == null ? null : schema.shown());
    }

    /**
     * Every packaged text and schema of one jar.
     *
     * @param texts bundle to language to key to its variants
     * @param schemas bundle to its described keys, sorted by bundle so the order does not follow the jar's
     */
    private record Packaged(
            Map<String, Map<String, Map<String, List<String>>>> texts, Map<String, List<SchemaEntry>> schemas) {}

    /** Opens the jar once and reads every packaged bundle and schema it carries. */
    private static Packaged readPackaged(final MessageBundleLocation location) throws IOException {
        final Map<String, Map<String, Map<String, List<String>>>> texts = new TreeMap<>();
        final Map<String, List<SchemaEntry>> schemas = new TreeMap<>();
        try (ZipFile jar = new ZipFile(location.jar().toFile())) {
            final Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                final Matcher schema = SCHEMA_ENTRY.matcher(entry.getName());
                final Matcher bundle = BUNDLE_ENTRY.matcher(entry.getName());
                if (schema.matches()) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        schemas.put(schema.group(1), readSchema(location, entry.getName(), in));
                    }
                } else if (bundle.matches()) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        texts.computeIfAbsent(bundle.group(1), ignored -> new HashMap<>())
                                .put(bundle.group(2), PackagedTexts.read(in, entry.getName()));
                    }
                }
            }
        }
        return new Packaged(texts, schemas);
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
     * One message's placeholders as the editor offers them, from the one validator's {@link Declaration}.
     * They are its own arguments, each role's attributes, the globals it does not name itself and its actions.
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
                entry.shown().name());
    }

    /** One placeholder per attribute of {@code role}, {@code role.attribute}, in the declaration's order. */
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
