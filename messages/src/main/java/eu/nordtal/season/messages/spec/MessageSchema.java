package eu.nordtal.season.messages.spec;

import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.PackagedTexts;
import eu.nordtal.season.messages.context.Contexts;
import eu.nordtal.season.messages.text.Declaration;
import eu.nordtal.season.messages.value.Action;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.Kind;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * The data of a {@link MessageSpec} for Steward.
 * The build writes it into the jar as {@code messages/<bundle>/schema.json} in the order of the
 * English file.
 */
public final class MessageSchema {

    private MessageSchema() {}

    /**
     * One argument of a message.
     *
     * @param name    the placeholder, the role of a context or the name of an action
     * @param kind    the kind's token, {@code null} for a context or an action
     * @param action  whether a text places it with {@code <action:name>}
     * @param takes   the keys that {@link Takes} names for a message value. It is {@code null}, and
     *                absent from the JSON, for other values and a message value without keys.
     */
    public record Arg(
            @Nullable String name,
            @Nullable String kind,
            @Nullable String context,
            @Nullable String example,
            boolean action,
            @Nullable List<String> takes) {

        public Arg(
                final @Nullable String name,
                final @Nullable String kind,
                final @Nullable String context,
                final @Nullable String example,
                final boolean action) {
            this(name, kind, context, example, action, null);
        }
    }

    /**
     * One key of a bundle.
     *
     * @param section  the names of the sections around the key, outermost first
     * @param shown    every place where the key is shown. An editor previews the first place first.
     *                 {@link MessageSpecCheck} refuses an empty list.
     * @param formerly the former names, {@code key} in this bundle or {@code bundle/key}. It is
     *                 {@code null}, and absent from the JSON, for a key that was never renamed.
     */
    public record Entry(
            String key,
            @Nullable String name,
            @Nullable String description,
            List<Arg> args,
            List<String> section,
            TextFormat format,
            List<Display> shown,
            @Nullable List<String> formerly) {

        /** Returns the strictest limit of the places, or {@code 0} for none. */
        public int limit() {
            return Display.strictest(shown);
        }
    }

    public static String bundle(final Class<?> spec) {
        final MessageSpec annotation = spec.getAnnotation(MessageSpec.class);
        if (annotation == null) {
            throw new IllegalArgumentException(spec.getName() + " is not a @MessageSpec interface");
        }
        return annotation.value();
    }

    /** Returns every key of the spec in the order of its English file. */
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
                nearest(null, spec.getAnnotation(Shown.class), List.of()));
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
            final List<Display> shown) {
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
                        nearest(ownShown, null, shown),
                        formerly == null ? null : List.of(formerly.value())));
            }
        }
    }

    private static TextFormat nearest(
            final @Nullable Format method, final @Nullable Format type, final TextFormat outer) {
        return method != null ? method.value() : type != null ? type.value() : outer;
    }

    private static List<Display> nearest(
            final @Nullable Shown method, final @Nullable Shown type, final List<Display> outer) {
        return method != null ? List.of(method.value()) : type != null ? List.of(type.value()) : outer;
    }

    private static Arg argOf(final Method method, final Parameter parameter) {
        final eu.nordtal.season.messages.spec.Arg arg =
                parameter.getAnnotation(eu.nordtal.season.messages.spec.Arg.class);
        return arg(method.toString(), arg == null ? null : arg.value(), parameter.getType(), parameter);
    }

    /**
     * Returns the argument of a value of {@code type}, read from its {@code @Example} and {@code @Takes}.
     * A message's parameter and a document's record component share it.
     *
     * @param where names the declaration in a refusal
     * @throws IllegalStateException if the type is no kind, or the annotations disagree with it
     */
    public static Arg arg(
            final String where, final @Nullable String name, final Class<?> type, final AnnotatedElement element) {
        if (Contexts.isContext(type)) {
            return new Arg(name, null, Contexts.type(type), null, false);
        }
        if (type == Action.class) {
            return new Arg(name, null, null, null, true);
        }
        final Kind kind = Kind.of(type)
                .orElseThrow(() -> new IllegalStateException(where + ": " + name + " is a " + type.getSimpleName()
                        + ", which is no kind a message can show"));
        final Example example = element.getAnnotation(Example.class);
        final Takes takes = element.getAnnotation(Takes.class);
        final boolean messages = kind == Kind.MESSAGE || listsMessages(element);
        if (takes != null && !messages) {
            throw new IllegalStateException(
                    where + ": " + name + " is a " + kind.token() + ", and only a message takes keys");
        }
        if (takes == null && kind == Kind.LIST && messages) {
            throw new IllegalStateException(where + ": " + name + " is a list of messages, so it needs @Takes");
        }
        final List<String> choices = takes == null ? Kind.choices(type) : List.of(takes.value());
        if (example == null && takes != null && whole(choices.getFirst())) {
            throw new IllegalStateException(
                    where + ": " + name + " takes " + choices.getFirst() + ", so it needs an @Example to preview");
        }
        if (example == null && choices.isEmpty()) {
            throw new IllegalStateException(where + ": " + name + " needs an @Example that fits what it names");
        }
        if (example != null
                && !choices.isEmpty()
                && !(kind == Kind.LIST ? Kind.items(example.value()) : List.of(example.value()))
                        .stream().allMatch(item -> takes(choices, item))) {
            throw new IllegalStateException(where + ": the example " + example.value() + " of " + name
                    + " is none of its values " + String.join(", ", choices));
        }
        final String shown = example != null ? example.value() : choices.getFirst();
        return new Arg(name, kind.token(), null, shown, false, takes == null ? null : choices);
    }

    /** Returns the context types that the messages name and the global roles use, by key. */
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

    static @Nullable String sectionName(final Method method) {
        final Name own = method.getAnnotation(Name.class);
        if (own != null) {
            return own.value();
        }
        final Name type = method.getReturnType().getAnnotation(Name.class);
        return type == null ? null : type.value();
    }

    /** Returns the variants of each key in one language, empty if the bundle has no file for it. */
    static Map<String, List<String>> texts(final Class<?> spec, final String language) {
        final Map<String, List<String>> texts = PackagedTexts.read(spec.getClassLoader(), bundle(spec), language);
        return texts == null ? Map.of() : texts;
    }

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

    public static String json(final Class<?> spec) {
        return Json.encode(of(spec)) + "\n";
    }

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

    public static Bundle decode(final java.io.Reader json) {
        return Json.decode(json, Bundle.class);
    }

    /** Returns whether {@code element} is a list of messages, whose items an example names as keys. */
    private static boolean listsMessages(final AnnotatedElement element) {
        final @Nullable Type type = switch (element) {
            case final RecordComponent component -> component.getGenericType();
            case final Parameter parameter -> parameter.getParameterizedType();
            default -> null;
        };
        return type instanceof final ParameterizedType list
                && List.class.isAssignableFrom((Class<?>) list.getRawType())
                && list.getActualTypeArguments()[0] == MessageRef.class;
    }

    /** Returns whether a key that {@link Takes} names is a whole bundle, {@code bundle/*}. */
    public static boolean whole(final String taken) {
        return taken.endsWith("/*");
    }

    /** Returns whether {@code key} is in {@code choices} or in a bundle that one names whole. */
    private static boolean takes(final List<String> choices, final String key) {
        return choices.contains(key)
                || choices.stream()
                        .anyMatch(choice -> whole(choice) && key.startsWith(choice.substring(0, choice.length() - 1)));
    }

    /**
     * The content of {@code schema.json}.
     *
     * @param messages the messages in the order of the English file
     * @param contexts the context types by key
     * @param globals  the roles of every message
     */
    public record Bundle(
            String bundle, List<Entry> messages, Map<String, ContextJson> contexts, List<GlobalJson> globals) {

        /** Returns what the texts of {@code entry} may name. */
        public Declaration declaration(final Entry entry) {
            final Map<String, Declaration.Placeholder> placeholders = new HashMap<>();
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
                    expand(arg.name(), arg.context(), placeholders);
                } else if (arg.kind() != null) {
                    placeholders.put(
                            arg.name(), new Declaration.Placeholder(arg.kind(), arg.example(), arg.takes() != null));
                }
            }
            for (final GlobalJson global : globals) {
                if (!roles.contains(global.name())) {
                    expand(global.name(), global.context(), placeholders);
                }
            }
            return Declaration.of(
                    placeholders,
                    roles,
                    actions,
                    entry.format() == TextFormat.MINIMESSAGE,
                    entry.limit(),
                    Display.oneLine(entry.shown()));
        }

        private void expand(
                final String role, final String type, final Map<String, Declaration.Placeholder> placeholders) {
            final ContextJson context = contexts.get(type);
            if (context == null) {
                throw new IllegalStateException(
                        "the role " + role + " has the type " + type + ", which the schema does not describe");
            }
            for (final AttributeJson attribute : context.attributes()) {
                placeholders.put(
                        role + "." + attribute.name(),
                        new Declaration.Placeholder(attribute.kind(), attribute.example(), false));
            }
        }
    }

    /**
     * A context type in the schema.
     *
     * @param name       the name an admin reads
     * @param attributes the attributes, with dots for those of a nested context
     */
    public record ContextJson(String name, List<AttributeJson> attributes) {}

    /**
     * One attribute of a context type.
     *
     * @param name the name, with dots for the attribute of a nested context
     */
    public record AttributeJson(String name, String kind, String example) {}

    /** A role of every message. */
    public record GlobalJson(String name, String context) {}

    /**
     * Writes {@code <output>/messages/<bundle>/schema.json} for a spec.
     *
     * @throws IllegalStateException if the spec disagrees with its bundle
     */
    public static void write(final Path output, final Class<?> spec) throws IOException {
        final List<String> problems = MessageSpecCheck.problems(spec);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    spec.getName() + " disagrees with its bundle:\n  " + String.join("\n  ", problems));
        }
        final Path file = output.resolve("messages").resolve(bundle(spec)).resolve("schema.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json(spec), StandardCharsets.UTF_8);
    }
}
