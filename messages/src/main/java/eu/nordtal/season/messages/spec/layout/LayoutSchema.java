package eu.nordtal.season.messages.spec.layout;

import eu.nordtal.season.common.Sha256;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.MessageSchema;
import eu.nordtal.season.messages.text.Declaration;
import eu.nordtal.season.messages.value.Kind;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Writes a module's layouts into {@code messages/<bundle>/layouts.json} at build time, beside the schema. */
public final class LayoutSchema {

    static final Pattern KEY = Pattern.compile("([a-z][a-z0-9-]*)/([A-Za-z0-9][A-Za-z0-9_.-]*)");

    private LayoutSchema() {}

    /**
     * @param values the placeholders a text placed in the layout may name: the union of its texts'
     *     values and the globals, without the line of a data list and the option line of a select,
     *     since each item or option fills them
     * @param shows every key the layout shows as {@code bundle/key}: its own, its elements' and
     *     every key a message value of one of them may take
     */
    public record Entry(Layout layout, Declaration values, List<String> shows) {

        public Entry {
            Objects.requireNonNull(layout, "layout");
            Objects.requireNonNull(values, "values");
            shows = List.copyOf(shows);
        }
    }

    record Found(MessageSchema.Bundle bundle, MessageSchema.Entry entry) {}

    /** Returns the schema of each bundle by name, from the module's specs or else from a jar on the classpath. */
    public static Function<String, Optional<MessageSchema.Bundle>> schemas(final List<Class<?>> specs) {
        final Map<String, Optional<MessageSchema.Bundle>> known = new HashMap<>();
        specs.forEach(spec -> known.put(MessageSchema.bundle(spec), Optional.of(MessageSchema.of(spec))));
        final ClassLoader loader = LayoutSchema.class.getClassLoader();
        return bundle -> known.computeIfAbsent(bundle, name -> packaged(loader, name));
    }

    private static Optional<MessageSchema.Bundle> packaged(final ClassLoader loader, final String bundle) {
        final String resource = "messages/" + bundle + "/schema.json";
        try (InputStream in = loader.getResourceAsStream(resource)) {
            return in == null
                    ? Optional.empty()
                    : Optional.of(MessageSchema.decode(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + resource, e);
        }
    }

    static Optional<Found> find(final String key, final Function<String, Optional<MessageSchema.Bundle>> schemas) {
        final Matcher matcher = KEY.matcher(key);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return schemas.apply(matcher.group(1))
                .flatMap(bundle -> bundle.messages().stream()
                        .filter(entry -> entry.key().equals(matcher.group(2)))
                        .findFirst()
                        .map(entry -> new Found(bundle, entry)));
    }

    /** Returns the layout's entry and reports every inconsistency to {@code problems}. */
    public static Entry describe(
            final Layout layout,
            final Function<String, Optional<MessageSchema.Bundle>> schemas,
            final Consumer<String> problems) {
        return new Entry(layout, values(layout, schemas, problems), shows(layout, schemas, problems));
    }

    private static Declaration values(
            final Layout layout,
            final Function<String, Optional<MessageSchema.Bundle>> schemas,
            final Consumer<String> problems) {
        final Map<String, Kind> values = new HashMap<>();
        final Map<String, String> namedBy = new HashMap<>();
        final Map<String, String> examples = new HashMap<>();
        final Set<String> actions = new HashSet<>();
        // Each item fills the line of a data list and each option the line of a select.
        final Set<String> lines = new HashSet<>();
        for (final Element element : layout.elements()) {
            switch (element.kind()) {
                case LIST -> lines.addAll(element.keys());
                case SELECT -> element.keys().stream().skip(1).forEach(lines::add);
                case BUTTON, LANGUAGES, LINK, INPUT -> {}
            }
        }
        for (final String key : layout.keys()) {
            final Optional<Found> found = lines.contains(key) ? Optional.empty() : find(key, schemas);
            if (found.isEmpty()) {
                continue;
            }
            final Declaration declaration =
                    found.get().bundle().declaration(found.get().entry());
            new TreeMap<>(declaration.values()).forEach((name, kind) -> {
                final Kind earlier = values.putIfAbsent(name, kind);
                if (earlier == null) {
                    namedBy.put(name, key);
                } else if (earlier != kind) {
                    problems.accept("names {" + name + "} as " + earlier.token() + " in " + namedBy.get(name)
                            + " and as " + kind.token() + " in " + key);
                }
            });
            declaration.examples().forEach(examples::putIfAbsent);
            actions.addAll(declaration.actions());
        }
        return new Declaration(values, Set.of(), actions, layout.surface() == Display.Surface.GAME, 0, examples, false);
    }

    private static List<String> shows(
            final Layout layout,
            final Function<String, Optional<MessageSchema.Bundle>> schemas,
            final Consumer<String> problems) {
        final Set<String> shown = new LinkedHashSet<>();
        final Deque<String> next = new ArrayDeque<>(layout.keys());
        final Map<String, String> takenBy = new HashMap<>();
        while (!next.isEmpty()) {
            final String key = next.removeFirst();
            if (!shown.add(key)) {
                continue;
            }
            final Optional<Found> found = find(key, schemas);
            if (found.isEmpty()) {
                if (takenBy.containsKey(key)) {
                    problems.accept(takenBy.get(key) + " takes " + key + ", which no bundle declares");
                }
                continue;
            }
            final String bundle = found.get().bundle().bundle();
            for (final MessageSchema.Arg arg : found.get().entry().args()) {
                for (final String taken : Objects.requireNonNullElse(arg.takes(), List.<String>of())) {
                    final String full = taken.contains("/") ? taken : bundle + "/" + taken;
                    final List<String> keys = MessageSchema.whole(full)
                            ? wholeBundle(full.substring(0, full.length() - 2), schemas)
                            : List.of(full);
                    for (final String each : keys) {
                        takenBy.putIfAbsent(each, key);
                        next.addLast(each);
                    }
                }
            }
        }
        return List.copyOf(shown);
    }

    /** Returns {@code bundle/*} when no schema exists. */
    private static List<String> wholeBundle(
            final String bundle, final Function<String, Optional<MessageSchema.Bundle>> schemas) {
        return schemas.apply(bundle)
                .map(found -> found.messages().stream()
                        .map(entry -> bundle + "/" + entry.key())
                        .toList())
                .orElse(List.of(bundle + "/*"));
    }

    /** Maps each key shown in two or more layouts to those layouts as {@code bundle/name}. */
    public static Map<String, List<String>> buildingBlocks(final Collection<Entry> entries) {
        final Map<String, List<String>> usages = new LinkedHashMap<>();
        for (final Entry entry : entries) {
            final String layout = entry.layout().bundle() + "/" + entry.layout().name();
            entry.shows()
                    .forEach(key -> usages.computeIfAbsent(key, ignored -> new ArrayList<>())
                            .add(layout));
        }
        usages.values().removeIf(layouts -> layouts.size() < 2);
        return usages;
    }

    /**
     * @throws IllegalArgumentException if {@code holder} declares no {@code static final} layout
     */
    public static List<Layout> declared(final Class<?> holder) {
        final List<Layout> layouts = new ArrayList<>();
        for (final Field field : holder.getDeclaredFields()) {
            final int modifiers = field.getModifiers();
            if (field.getType() == Layout.class && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)) {
                try {
                    field.setAccessible(true);
                    layouts.add((Layout) Objects.requireNonNull(field.get(null), field.toString()));
                } catch (final IllegalAccessException e) {
                    throw new IllegalStateException("cannot read " + field, e);
                }
            }
        }
        if (layouts.isEmpty()) {
            throw new IllegalArgumentException(holder.getName() + " declares no static final Layout");
        }
        return layouts;
    }

    /**
     * Writes {@code <output>/messages/<bundle>/layouts.json} for each bundle a layout is listed under.
     *
     * @throws IllegalStateException if a layout is not sound or is listed under a bundle none of {@code specs} ships
     */
    public static void write(final Path output, final List<Class<?>> specs, final List<Layout> layouts)
            throws IOException {
        final Function<String, Optional<MessageSchema.Bundle>> schemas = schemas(specs);
        final List<String> problems = new ArrayList<>(LayoutCheck.problems(layouts, schemas));
        final Set<String> shipped = new HashSet<>();
        specs.forEach(spec -> shipped.add(MessageSchema.bundle(spec)));
        final Map<String, List<Entry>> byBundle = new TreeMap<>();
        for (final Layout layout : layouts) {
            if (!shipped.contains(layout.bundle())) {
                problems.add(layout.bundle() + "/" + layout.name() + ": is listed under " + layout.bundle()
                        + ", which none of the module's specs ships");
            }
            byBundle.computeIfAbsent(layout.bundle(), ignored -> new ArrayList<>())
                    .add(describe(layout, schemas, problem -> {}));
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "the layouts disagree with their specs:\n  " + String.join("\n  ", problems));
        }
        for (final Map.Entry<String, List<Entry>> bundle : byBundle.entrySet()) {
            final Path file = output.resolve(Path.of("messages", bundle.getKey(), "layouts.json"));
            Files.createDirectories(file.getParent());
            Files.writeString(file, json(bundle.getValue()), StandardCharsets.UTF_8);
        }
    }

    public static String hash(final Layout layout) {
        return Sha256.hex(Json.encode(layout));
    }

    public static String json(final List<Entry> entries) {
        return Json.encode(entries) + "\n";
    }

    public static List<Entry> decode(final String json) {
        return List.of(Json.decode(json, Entry[].class));
    }
}
