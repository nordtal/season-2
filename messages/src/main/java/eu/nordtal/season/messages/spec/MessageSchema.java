package eu.nordtal.season.messages.spec;

import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.messages.PackagedTexts;
import eu.nordtal.season.messages.context.Contexts;
import eu.nordtal.season.messages.text.Declaration;
import eu.nordtal.season.messages.value.Action;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.Kind;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * What a {@link MessageSpec} says about its bundle, as data, for steward.
 *
 * Written into the jar as {@code messages/<bundle>/schema.json} at build time, in the order of the English file.
 */
public final class MessageSchema {

    private MessageSchema() {}

    /**
     * One argument of a message.
     *
     * @param name    the placeholder, or for a context the role, or for an action its name
     * @param kind    the kind's token of a single value, or {@code null} for a context or an action
     * @param context the context type of a role, or {@code null}
     * @param example a single value's example, or {@code null}
     * @param action  whether it is an action a text places with {@code <action:name>}
     */
    public record Arg(
            @Nullable String name,
            @Nullable String kind,
            @Nullable String context,
            @Nullable String example,
            boolean action) {}

    /**
     * @param key         the bundle key
     * @param name        the name an admin reads
     * @param description a sentence for a hard case, or {@code null}
     * @param args        the placeholders, in parameter order
     * @param section     the names of the sections around it, outermost first
     * @param format      how it is written
     * @param shown       where it is shown
     * @param formerly    the names it had before, {@code key} in this bundle or {@code bundle/key}; {@code null},
     *                    and absent from the JSON, for a key never renamed
     */
    public record Entry(
            String key,
            @Nullable String name,
            @Nullable String description,
            List<Arg> args,
            List<String> section,
            TextFormat format,
            Display shown,
            @Nullable List<String> formerly) {}

    /** Returns the bundle a spec describes. */
    public static String bundle(final Class<?> spec) {
        final MessageSpec annotation = spec.getAnnotation(MessageSpec.class);
        if (annotation == null) {
            throw new IllegalArgumentException(spec.getName() + " is not a @MessageSpec interface");
        }
        return annotation.value();
    }

    /** Returns every key the spec declares, in the order of its English file. */
    public static List<Entry> entries(final Class<?> spec) {
        final List<Entry> entries = new ArrayList<>();
        final MessageSpec annotation = spec.getAnnotation(MessageSpec.class);
        walk(
                spec,
                "",
                List.of(),
                entries,
                0,
                nearest(null, spec.getAnnotation(Format.class), annotation.format()),
                nearest(null, spec.getAnnotation(Shown.class), annotation.shown()));
        final Map<String, Integer> order = new LinkedHashMap<>();
        for (final String key : fileOrder(spec)) {
            order.putIfAbsent(key, order.size());
        }
        entries.sort(Comparator.comparingInt((Entry entry) -> order.getOrDefault(entry.key(), Integer.MAX_VALUE))
                .thenComparing(Entry::key));
        return entries;
    }

    private static void walk(
            final Class<?> type,
            final String prefix,
            final List<String> section,
            final List<Entry> into,
            final int depth,
            final TextFormat format,
            final Display shown) {
        if (depth > 16) {
            throw new IllegalStateException(type.getName() + " nests sections more than 16 deep - a cycle?");
        }
        for (final Method method : type.getMethods()) {
            if (MessageSpecs.isSection(method)) {
                final List<String> inner = new ArrayList<>(section);
                inner.add(sectionName(method));
                final Class<?> inside = method.getReturnType();
                walk(
                        inside,
                        prefix + MessageSpecs.segment(method) + ".",
                        inner,
                        into,
                        depth + 1,
                        nearest(method.getAnnotation(Format.class), inside.getAnnotation(Format.class), format),
                        nearest(method.getAnnotation(Shown.class), inside.getAnnotation(Shown.class), shown));
            } else if (MessageSpecs.isKey(method)) {
                final Name name = method.getAnnotation(Name.class);
                final Describe describe = method.getAnnotation(Describe.class);
                final List<Arg> args = new ArrayList<>();
                for (final Parameter parameter : method.getParameters()) {
                    args.add(argOf(method, parameter));
                }
                final Format ownFormat = method.getAnnotation(Format.class);
                final Shown ownShown = method.getAnnotation(Shown.class);
                final Formerly formerly = method.getAnnotation(Formerly.class);
                into.add(new Entry(
                        prefix + MessageSpecs.segment(method),
                        name == null ? null : name.value(),
                        describe == null ? null : describe.value(),
                        List.copyOf(args),
                        List.copyOf(section),
                        ownFormat == null ? format : ownFormat.value(),
                        ownShown == null ? shown : ownShown.value(),
                        formerly == null ? null : List.of(formerly.value())));
            }
        }
    }

    private static TextFormat nearest(
            final @Nullable Format method, final @Nullable Format type, final TextFormat outer) {
        return method != null ? method.value() : type != null ? type.value() : outer;
    }

    private static Display nearest(final @Nullable Shown method, final @Nullable Shown type, final Display outer) {
        return method != null ? method.value() : type != null ? type.value() : outer;
    }

    private static Arg argOf(final Method method, final Parameter parameter) {
        final eu.nordtal.season.messages.spec.Arg arg =
                parameter.getAnnotation(eu.nordtal.season.messages.spec.Arg.class);
        final String name = arg == null ? null : arg.value();
        final Class<?> type = parameter.getType();
        if (Contexts.isContext(type)) {
            return new Arg(name, null, Contexts.type(type), null, false);
        }
        if (type == Action.class) {
            return new Arg(name, null, null, null, true);
        }
        final Kind kind = Kind.of(type)
                .orElseThrow(() -> new IllegalStateException(method + ": " + name + " is a " + type.getSimpleName()
                        + ", which is no kind a message can show"));
        final Example example = parameter.getAnnotation(Example.class);
        return new Arg(name, kind.token(), null, example == null ? kind.defaultExample() : example.value(), false);
    }

    /** Returns the context types a spec's messages name, plus those of the global roles, by key. */
    static Map<String, Class<?>> contextTypes(final Class<?> spec) {
        final Map<String, Class<?>> types = new TreeMap<>();
        collect(spec, types, 0);
        Contexts.GLOBALS.values().forEach(type -> types.put(Contexts.type(type), type));
        return types;
    }

    private static void collect(final Class<?> type, final Map<String, Class<?>> into, final int depth) {
        if (depth > 16) {
            return;
        }
        for (final Method method : type.getMethods()) {
            if (MessageSpecs.isSection(method)) {
                collect(method.getReturnType(), into, depth + 1);
            } else if (MessageSpecs.isKey(method)) {
                for (final Class<?> parameter : method.getParameterTypes()) {
                    if (Contexts.isContext(parameter)) {
                        into.put(Contexts.type(parameter), parameter);
                    }
                }
            }
        }
    }

    /** A section's name: on the method that opens it, or on its interface. */
    static @Nullable String sectionName(final Method method) {
        final Name own = method.getAnnotation(Name.class);
        if (own != null) {
            return own.value();
        }
        final Name type = method.getReturnType().getAnnotation(Name.class);
        return type == null ? null : type.value();
    }

    /** One language of the spec's bundle, key to its variants; empty when the bundle has no such file. */
    static Map<String, List<String>> texts(final Class<?> spec, final String language) {
        final Map<String, List<String>> texts = PackagedTexts.read(spec.getClassLoader(), bundle(spec), language);
        return texts == null ? Map.of() : texts;
    }

    /** The keys of the English file in the order they are written. */
    private static List<String> fileOrder(final Class<?> spec) {
        final String resource = "messages/" + bundle(spec) + "/en.properties";
        final List<String> keys = new ArrayList<>();
        try (InputStream in = spec.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return keys;
            }
            final String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            boolean continued = false;
            for (final String line : text.split("\n", -1)) {
                final String trimmed = line.strip();
                final boolean wasContinued = continued;
                continued = trimmed.endsWith("\\") && !trimmed.endsWith("\\\\");
                if (wasContinued || trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue;
                }
                final int end = firstSeparator(trimmed);
                keys.add(PackagedTexts.keyOf(trimmed.substring(0, end).strip()));
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + resource, e);
        }
        return keys;
    }

    private static int firstSeparator(final String line) {
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (c == '=' || c == ':' || c == ' ') {
                return i;
            }
        }
        return line.length();
    }

    /** Returns the schema as the JSON steward and every process read. */
    public static String json(final Class<?> spec) {
        return Json.encode(of(spec)) + "\n";
    }

    /** Returns the schema of a spec. */
    public static Bundle of(final Class<?> spec) {
        final Map<String, ContextJson> contexts = new LinkedHashMap<>();
        contextTypes(spec)
                .forEach((type, context) -> contexts.put(
                        type,
                        new ContextJson(
                                Contexts.name(context),
                                Contexts.attributes(context).stream()
                                        .map(attribute -> new AttributeJson(
                                                attribute.name(),
                                                attribute.kind().token(),
                                                attribute.example()))
                                        .toList())));
        final List<GlobalJson> globals = Contexts.GLOBAL_ROLES.stream()
                .map(role ->
                        new GlobalJson(role, Contexts.type(Objects.requireNonNull(Contexts.GLOBALS.get(role), role))))
                .toList();
        return new Bundle(bundle(spec), entries(spec), contexts, globals);
    }

    /** Reads a {@code schema.json}. */
    public static Bundle decode(final java.io.Reader json) {
        return Json.decode(json, Bundle.class);
    }

    /**
     * The shape of {@code schema.json}: every message of a bundle, the context types they name and the globals.
     *
     * @param bundle   the bundle
     * @param messages its messages, in the order of the English file
     * @param contexts each context type by key
     * @param globals  the roles every message has
     */
    public record Bundle(
            String bundle, List<Entry> messages, Map<String, ContextJson> contexts, List<GlobalJson> globals) {

        /** Returns what {@code entry}'s texts may name, as the one validator reads it. */
        public Declaration declaration(final Entry entry) {
            final Map<String, Kind> values = new HashMap<>();
            final Map<String, String> examples = new HashMap<>();
            final Set<String> roles = new HashSet<>();
            final Set<String> actions = new HashSet<>();
            for (final Arg arg : entry.args()) {
                if (arg.name() == null) {
                    continue;
                }
                if (arg.action()) {
                    actions.add(arg.name());
                    continue;
                }
                roles.add(arg.name());
                if (arg.context() != null) {
                    expand(arg.name(), arg.context(), values, examples);
                } else if (arg.kind() != null) {
                    values.put(arg.name(), Kind.byToken(arg.kind()).orElse(Kind.TEXT));
                    if (arg.example() != null) {
                        examples.put(arg.name(), arg.example());
                    }
                }
            }
            for (final GlobalJson global : globals) {
                if (!roles.contains(global.name())) {
                    expand(global.name(), global.context(), values, examples);
                }
            }
            return new Declaration(
                    values,
                    roles,
                    actions,
                    entry.format() == TextFormat.MINIMESSAGE,
                    entry.shown().limit(),
                    examples);
        }

        private void expand(
                final String role,
                final String type,
                final Map<String, Kind> values,
                final Map<String, String> examples) {
            final ContextJson context = contexts.get(type);
            if (context == null) {
                throw new IllegalStateException(
                        "the role " + role + " has the type " + type + ", which the schema does not describe");
            }
            for (final AttributeJson attribute : context.attributes()) {
                values.put(
                        role + "." + attribute.name(),
                        Kind.byToken(attribute.kind()).orElse(Kind.TEXT));
                examples.put(role + "." + attribute.name(), attribute.example());
            }
        }
    }

    /**
     * A context type as the schema lists it.
     *
     * @param name       the name an admin reads
     * @param attributes its attributes, a nested context's dotted
     */
    public record ContextJson(String name, List<AttributeJson> attributes) {}

    /**
     * One attribute of a context type.
     *
     * @param name    the attribute, dotted for a nested context's
     * @param kind    its kind's token
     * @param example what an editor shows for it
     */
    public record AttributeJson(String name, String kind, String example) {}

    /**
     * A role every message has.
     *
     * @param name    the role
     * @param context its context type
     */
    public record GlobalJson(String name, String context) {}

    /**
     * Writes {@code <output directory>/messages/<bundle>/schema.json} per spec, refusing a spec the check faults.
     */
    public static void main(final String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: MessageSchema <output directory> <spec class>...");
        }
        for (final String name : Arrays.asList(args).subList(1, args.length)) {
            final Class<?> spec = Class.forName(name);
            final List<String> problems = MessageSpecCheck.problems(spec);
            if (!problems.isEmpty()) {
                throw new IllegalStateException(
                        spec.getName() + " disagrees with its bundle:\n  " + String.join("\n  ", problems));
            }
            final Path file = Path.of(args[0], "messages", bundle(spec), "schema.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, json(spec), StandardCharsets.UTF_8);
        }
    }
}
