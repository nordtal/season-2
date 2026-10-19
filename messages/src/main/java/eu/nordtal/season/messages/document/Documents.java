package eu.nordtal.season.messages.document;

import eu.nordtal.season.common.Sha256;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.messages.spec.MessageSchema;
import eu.nordtal.season.messages.text.MessageCheck;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Reads the documents a jar ships and writes their declarations at build time. */
public final class Documents {

    private Documents() {}

    /** A packaged document with its declaration. */
    public record Packaged(DocumentDeclaration declaration, Document document) {

        /** Identifies the release's version of the document, which an admin's document records it replaced. */
        public String hash() {
            return Sha256.hex(Json.encode(declaration) + "\n" + Json.encode(document));
        }
    }

    /** Where a process's documents come from. */
    @FunctionalInterface
    public interface Source {

        /**
         * Returns the packaged declaration and the document shown for {@code spec}: an admin's, else the packaged one.
         *
         * @throws IllegalStateException if no such document is packaged
         */
        Packaged applied(DocumentSpec<?> spec);
    }

    public static String resource(final String namespace, final String name) {
        return "documents/" + namespace + "/" + name + ".json";
    }

    public static String declarations(final String namespace) {
        return "documents/" + namespace + "/declarations.json";
    }

    /**
     * @throws IllegalArgumentException if {@code holder} declares no {@code static final} document
     */
    public static List<DocumentSpec<?>> declared(final Class<?> holder) {
        final List<DocumentSpec<?>> specs = new ArrayList<>();
        for (final Field field : holder.getDeclaredFields()) {
            final int modifiers = field.getModifiers();
            if (field.getType() == DocumentSpec.class && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)) {
                try {
                    field.setAccessible(true);
                    specs.add((DocumentSpec<?>) Objects.requireNonNull(field.get(null), field.toString()));
                } catch (final IllegalAccessException e) {
                    throw new IllegalStateException("cannot read " + field, e);
                }
            }
        }
        if (specs.isEmpty()) {
            throw new IllegalArgumentException(holder.getName() + " declares no static final DocumentSpec");
        }
        return specs;
    }

    /**
     * @throws IllegalStateException if the jar lacks the resource
     */
    public static String read(final ClassLoader loader, final String resource) {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + resource, e);
        }
    }

    /** Returns each document of {@code namespace} that the jar ships, by name, or none without declarations. */
    public static Map<String, Packaged> packaged(final ClassLoader loader, final String namespace) {
        final Map<String, Packaged> packaged = new TreeMap<>();
        if (loader.getResource(declarations(namespace)) == null) {
            return packaged;
        }
        for (final DocumentDeclaration declaration : decode(read(loader, declarations(namespace)))) {
            packaged.put(
                    declaration.name(),
                    new Packaged(declaration, Document.decode(read(loader, resource(namespace, declaration.name())))));
        }
        return packaged;
    }

    public static List<DocumentDeclaration> decode(final String json) {
        return List.of(Json.decode(json, DocumentDeclaration[].class));
    }

    /**
     * Writes each spec's {@code schema.json} and each namespace's {@code declarations.json}; the build runs it.
     * A spec or a document that fails a check is refused.
     */
    public static void main(final String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException(
                    "usage: Documents <output directory> <spec class>... [--documents <class>...]");
        }
        final Path output = Path.of(args[0]);
        final List<String> names = Arrays.asList(args).subList(1, args.length);
        final int split = names.contains("--documents") ? names.indexOf("--documents") : names.size();
        for (final String name : names.subList(0, split)) {
            MessageSchema.write(output, Class.forName(name));
        }
        final List<DocumentSpec<?>> documents = new ArrayList<>();
        for (final String holder : names.subList(Math.min(split + 1, names.size()), names.size())) {
            documents.addAll(declared(Class.forName(holder)));
        }
        write(output, documents, Documents.class.getClassLoader());
    }

    /**
     * Checks each document against its declaration and writes {@code <output>/documents/<namespace>/declarations.json}.
     *
     * @throws IllegalStateException if a document is missing, declared twice or refused by {@link DocumentCheck}
     */
    public static void write(final Path output, final List<DocumentSpec<?>> specs, final ClassLoader loader)
            throws IOException {
        final List<String> problems = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        final Map<String, List<DocumentDeclaration>> byNamespace = new TreeMap<>();
        for (final DocumentSpec<?> spec : specs) {
            if (!seen.add(spec.id())) {
                problems.add(spec.id() + ": declared more than once");
            }
            final DocumentDeclaration declaration = spec.declaration();
            byNamespace
                    .computeIfAbsent(spec.namespace(), ignored -> new ArrayList<>())
                    .add(declaration);
            if (loader.getResource(spec.resource()) == null) {
                problems.add(spec.id() + ": " + spec.resource() + " is missing");
                continue;
            }
            final Document document;
            try {
                document = Document.decode(read(loader, spec.resource()));
            } catch (final RuntimeException unreadable) {
                problems.add(spec.id() + ": " + spec.resource() + " is no document: " + unreadable.getMessage());
                continue;
            }
            DocumentCheck.errors(declaration, document, MessageCheck.Mode.PACKAGED)
                    .forEach(problem -> problems.add(spec.id() + ": " + problem));
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "the documents disagree with their declarations:\n  " + String.join("\n  ", problems));
        }
        for (final Map.Entry<String, List<DocumentDeclaration>> namespace : byNamespace.entrySet()) {
            namespace.getValue().sort(Comparator.comparing(DocumentDeclaration::name));
            final Path file = output.resolve(declarations(namespace.getKey()));
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.encode(namespace.getValue()) + "\n", StandardCharsets.UTF_8);
        }
    }
}
