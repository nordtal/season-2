package eu.nordtal.season.steward;

import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.MessageSchema;
import eu.nordtal.season.steward.texts.Shell;
import eu.nordtal.season.steward.texts.StewardTexts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes the Steward page that each of Steward's own texts is on.
 *
 * A page holds the literal keys in the code its route's component reaches, and the texts the server answers with on
 * the routes that code calls. A text the shell reaches is on every page. {@code :steward:generateApiTypes} runs it;
 * {@code TextPagesTest} fails while the committed file differs, and {@link #pages()} refuses a text on no page.
 */
public final class TextPages {

    static final Path TARGET = Path.of("frontend/src/lib/text-pages.gen.json");

    private static final Path SOURCES = Path.of("frontend/src");

    private static final String EVERY = page(Shell.Page.EVERY);

    /** An import with a clause: whether it is a type import, the specifier, and the names it binds. */
    private static final Pattern IMPORT =
            Pattern.compile("^import\\s+(type\\s+)?([^;]*?)\\s+from\\s+\"([^\"]+)\"", Pattern.MULTILINE);

    /** An import for its effect or a lazy import, each taking the whole module. */
    private static final Pattern WHOLE =
            Pattern.compile("^import\\s+\"([^\"]+)\"|import\\(\\s*\"([^\"]+)\"\\s*\\)", Pattern.MULTILINE);

    /** A re-export: the names it passes on, none for all of them, and the specifier. */
    private static final Pattern REEXPORT =
            Pattern.compile("^export\\s+(?:\\*|\\{([^}]*)})\\s+from\\s+\"([^\"]+)\"", Pattern.MULTILINE);

    /** A top-level declaration and its name. */
    private static final Pattern DECLARATION = Pattern.compile(
            "^(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?(?:function\\*?|const|let|var|class|type|interface|enum)\\s+(\\w+)",
            Pattern.MULTILINE);

    private static final Pattern KEY = Pattern.compile("[\"'`](steward\\.[a-z0-9.-]+)[\"'`]");

    private static final Pattern ROUTE_PATH = Pattern.compile("path:\\s*\"([^\"]+)\"");

    private static final Pattern COMPONENT = Pattern.compile(
            "(?:component|notFoundComponent):\\s*(?:lazyRouteComponent\\(\\(\\) => import\\(\"([^\"]+)\"\\)|(\\w+))");

    private static final Set<String> REGISTERS = Set.of("get", "post", "put", "patch", "delete", "head", "sse");

    /** The Javalin calls that register a handler run before every route, which the route {@code *} stands for. */
    private static final Set<String> GUARDS = Set.of("before", "beforeMatched");

    /** Discord sends the browser from the sign-in back to the callback, which answers in that tab. */
    private static final Map<String, String> HOPS = Map.of("/auth/login", "/auth/callback");

    private TextPages() {}

    /** The page map: the pages in the router's order, the texts only the server answers with, and each text's pages. */
    static String render() {
        final Frontend frontend = new Frontend();
        final Map<String, Set<String>> pages = pages(frontend);
        final StringBuilder json = new StringBuilder("{\n  \"pages\": ")
                .append(Json.encode(List.copyOf(frontend.pages.keySet())))
                .append(",\n  \"answers\": ")
                .append(Json.encode(answeredOnly(frontend, pages.keySet())))
                .append(",\n  \"texts\": {\n");
        int left = pages.size();
        for (final Map.Entry<String, Set<String>> entry : pages.entrySet()) {
            json.append("    \"").append(entry.getKey()).append("\": ").append(Json.encode(entry.getValue()));
            json.append(--left > 0 ? ",\n" : "\n");
        }
        return json.append("  }\n}\n").toString();
    }

    /** Every key of Steward's bundle with the pages it is on; a text the shell reaches is on {@code every} alone. */
    static Map<String, Set<String>> pages() {
        return pages(new Frontend());
    }

    private static Map<String, Set<String>> pages(final Frontend frontend) {
        final Map<String, Set<String>> answered = Server.answers();
        final Map<String, Set<String>> on = new TreeMap<>();
        frontend.pages.forEach((page, code) -> {
            keysIn(code)
                    .forEach(key ->
                            on.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(page));
            for (final String named : pathsIn(code)) {
                for (final String path : Stream.of(named, HOPS.get(named))
                        .filter(Objects::nonNull)
                        .toList()) {
                    answered.forEach((route, keys) -> {
                        if (Server.matches(route, path)) {
                            keys.forEach(key -> on.computeIfAbsent(key, k -> new LinkedHashSet<>())
                                    .add(page));
                        }
                    });
                }
            }
        });
        final Map<String, Set<String>> placed = new TreeMap<>();
        final List<String> nowhere = new ArrayList<>();
        for (final String key : keys()) {
            final Set<String> found = on.getOrDefault(key, Set.of());
            if (found.isEmpty()) {
                nowhere.add(key);
                continue;
            }
            final List<String> ordered =
                    frontend.pages.keySet().stream().filter(found::contains).toList();
            placed.put(key, ordered.contains(EVERY) ? Set.of(EVERY) : new LinkedHashSet<>(ordered));
        }
        if (!nowhere.isEmpty()) {
            throw new IllegalStateException("these texts are on no Steward page: no page's code names them and no"
                    + " route a page calls answers with them: " + nowhere);
        }
        return placed;
    }

    /** The texts no page's code names, which reach a page only as the server's answer. */
    private static List<String> answeredOnly(final Frontend frontend, final Set<String> keys) {
        final Set<String> named = new HashSet<>();
        frontend.pages.values().forEach(code -> named.addAll(keysIn(code)));
        return keys.stream().filter(key -> !named.contains(key)).toList();
    }

    private static List<String> keys() {
        return MessageSchema.entries(StewardTexts.class).stream()
                .map(MessageSchema.Entry::key)
                .toList();
    }

    /** A page's name as `steward.shell.page` takes it. */
    private static String page(final Shell.Page page) {
        return page.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static Set<String> keysIn(final String code) {
        final Set<String> keys = new TreeSet<>();
        final Matcher matcher = KEY.matcher(code);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    /** The API paths the code names, each interpolation as {@code {}} and without its query. */
    private static List<String> pathsIn(final String code) {
        final List<String> paths = new ArrayList<>();
        for (int at = 0; at < code.length(); at++) {
            final char quote = code.charAt(at);
            if ((quote != '"' && quote != '\'' && quote != '`')
                    || !(code.startsWith("/api/", at + 1) || code.startsWith("/auth/", at + 1))) {
                continue;
            }
            final StringBuilder path = new StringBuilder();
            int end = at + 1;
            while (end < code.length() && code.charAt(end) != quote) {
                if (quote == '`' && code.startsWith("${", end)) {
                    end++;
                    int depth = 0;
                    do {
                        final char c = code.charAt(end++);
                        depth += c == '{' ? 1 : c == '}' ? -1 : 0;
                    } while (depth > 0);
                    path.append("{}");
                } else {
                    path.append(code.charAt(end++));
                }
            }
            at = end;
            final int query = path.indexOf("?");
            final String plain = query < 0 ? path.toString() : path.substring(0, query);
            // A path that ends in a slash has its last segment added on.
            paths.add(plain.endsWith("/") ? plain + "{}" : plain);
        }
        return paths;
    }

    /**
     * The frontend's modules, each cut into its top-level declarations, and the code each page reaches.
     *
     * A page reaches only the declarations it uses, so a page that takes one hook from a module of many does not
     * take the others' texts and paths along.
     */
    private static final class Frontend {

        /** One module: what each local name imports, as {module, name}, its declarations, and the rest of it. */
        private record Module(
                Map<String, List<String>> imports,
                Set<String> whole,
                List<String> passesOn,
                Map<String, String> declarations,
                String body) {}

        private final Map<String, String> sources = new HashMap<>();

        private final Map<String, Module> modules = new HashMap<>();

        /** Each page, in the router's order, to the code it reaches. */
        private final Map<String, String> pages = new LinkedHashMap<>();

        Frontend() {
            try (Stream<Path> files = Files.walk(SOURCES)) {
                for (final Path file : files.toList()) {
                    final String name = SOURCES.relativize(file).toString().replace('\\', '/');
                    if (name.matches(".*\\.tsx?") && !name.matches(".*(\\.test\\.|\\.gen\\.|fixtures).*")) {
                        sources.put(name, Files.readString(file, StandardCharsets.UTF_8));
                    }
                }
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            sources.keySet().forEach(name -> modules.put(name, module(name)));
            final Module router = modules.get("router.tsx");
            final Map<String, StringBuilder> code = new LinkedHashMap<>();
            for (final String route : sources.get("router.tsx").split("create(?=(?:Root)?Route\\()", -1)) {
                final Matcher path = ROUTE_PATH.matcher(route);
                final String page = route.startsWith("RootRoute(") ? EVERY : path.find() ? pageOf(path.group(1)) : null;
                final Matcher component = COMPONENT.matcher(route);
                while (page != null && component.find()) {
                    final List<String> start = component.group(2) != null
                            ? router.imports().get(component.group(2))
                            : List.of(Objects.requireNonNull(resolve("router.tsx", component.group(1)), route), "*");
                    if (start == null) {
                        throw new IllegalStateException("router.tsx names a component it does not import: " + route);
                    }
                    code.computeIfAbsent(page, p -> new StringBuilder()).append(reached(start));
                }
            }
            code.forEach((page, reached) -> pages.put(page, reached.toString()));
        }

        /** The page a route is: its last plain segment that names one, or the overview for the root. */
        private static String pageOf(final String path) {
            final Set<String> known = new HashSet<>();
            for (final Shell.Page page : Shell.Page.values()) {
                known.add(page(page));
            }
            String found = path.equals("/") ? page(Shell.Page.OVERVIEW) : null;
            for (final String segment : path.split("/", -1)) {
                if (known.contains(segment)) {
                    found = segment;
                }
            }
            if (found == null) {
                throw new IllegalStateException("the route " + path + " names no page of Shell.Page");
            }
            return found;
        }

        /** Each local name an import binds, to {module, name}, {@code *} for a whole module. */
        private Map<String, List<String>> imports(final String name, final String source) {
            final Map<String, List<String>> imports = new HashMap<>();
            final Matcher from = IMPORT.matcher(source);
            while (from.find()) {
                final String module = resolve(name, from.group(3));
                // A type import brings no code along.
                if (module == null || from.group(1) != null) {
                    continue;
                }
                final String clause = from.group(2);
                final Matcher braces = Pattern.compile("\\{([^}]*)}").matcher(clause);
                while (braces.find()) {
                    bind(braces.group(1), module, imports);
                }
                for (final String rest : clause.replaceAll("\\{[^}]*}", "").split(",", -1)) {
                    final String local = rest.replace("* as ", "").strip();
                    if (!local.isEmpty()) {
                        imports.put(local, List.of(module, "*"));
                    }
                }
            }
            return imports;
        }

        /** Binds each name of a {@code { a, b as c }} list to the module's export of it, a type to nothing. */
        private static void bind(final String list, final String module, final Map<String, List<String>> imports) {
            for (final String part : list.split(",", -1)) {
                final String[] names = part.strip().split("\\s+as\\s+", -1);
                if (!names[0].isEmpty() && !names[0].startsWith("type ")) {
                    imports.put(names[names.length - 1].strip(), List.of(module, names[0].strip()));
                }
            }
        }

        private Module module(final String name) {
            final String source = sources.get(name);
            final Map<String, List<String>> imports = imports(name, source);
            final Set<String> whole = new HashSet<>();
            final Matcher bare = WHOLE.matcher(source);
            while (bare.find()) {
                final String module = resolve(
                        name,
                        Stream.of(bare.group(1), bare.group(2))
                                .filter(Objects::nonNull)
                                .findFirst()
                                .orElseThrow());
                if (module != null) {
                    whole.add(module);
                }
            }
            // A name re-exported by name is an import of it; `export *` passes on whatever the module lacks.
            final List<String> passesOn = new ArrayList<>();
            final Matcher reexport = REEXPORT.matcher(source);
            while (reexport.find()) {
                final String module = resolve(name, reexport.group(2));
                if (module == null) {
                    continue;
                }
                if (reexport.group(1) == null) {
                    passesOn.add(module);
                    continue;
                }
                bind(reexport.group(1), module, imports);
            }
            final Map<String, String> declarations = new HashMap<>();
            final Matcher declaration = DECLARATION.matcher(source);
            int body = source.length();
            String last = null;
            int lastAt = 0;
            while (declaration.find()) {
                if (last == null) {
                    body = declaration.start();
                } else {
                    declarations.merge(last, source.substring(lastAt, declaration.start()), String::concat);
                }
                last = declaration.group(1);
                lastAt = declaration.start();
            }
            if (last != null) {
                declarations.merge(last, source.substring(lastAt), String::concat);
            }
            return new Module(
                    imports,
                    whole,
                    passesOn,
                    declarations,
                    source.substring(0, body).replaceAll("(?m)^import[^\\n]*\\n", ""));
        }

        /** The code a module's export reaches, {@code *} for the whole module, following each name it uses. */
        private String reached(final List<String> start) {
            final StringBuilder code = new StringBuilder();
            final Set<List<String>> seen = new HashSet<>();
            final ArrayDeque<List<String>> left = new ArrayDeque<>(List.of(start));
            while (!left.isEmpty()) {
                final List<String> next = left.pop();
                if (!seen.add(next)) {
                    continue;
                }
                final String name = next.get(0);
                final String symbol = next.get(1);
                final Module module = modules.get(name);
                final List<String> parts = new ArrayList<>();
                if (symbol.equals("*")) {
                    parts.addAll(module.declarations().values());
                } else if (module.declarations().containsKey(symbol)) {
                    parts.add(module.declarations().get(symbol));
                } else if (module.imports().containsKey(symbol)) {
                    left.push(module.imports().get(symbol));
                } else {
                    module.passesOn().forEach(other -> left.push(List.of(other, symbol)));
                }
                // A module's own statements run whenever anything of it is used, and its bare imports with them.
                if (seen.add(List.of(name, "#body"))) {
                    parts.add(module.body());
                    module.whole().forEach(other -> left.push(List.of(other, "*")));
                }
                for (final String part : parts) {
                    code.append(part).append('\n');
                    final Matcher word = Pattern.compile("[A-Za-z_$][\\w$]*").matcher(part);
                    while (word.find()) {
                        final String used = word.group();
                        if (!used.equals(symbol) && module.declarations().containsKey(used)) {
                            left.push(List.of(name, used));
                        }
                        if (module.imports().containsKey(used)) {
                            left.push(module.imports().get(used));
                        }
                    }
                }
            }
            return code.toString();
        }

        /** The module a specifier names, or null for a package or a file that is not a module of its own. */
        private String resolve(final String from, final String specifier) {
            final String base;
            if (specifier.startsWith("@/")) {
                base = specifier.substring(2);
            } else if (specifier.startsWith(".")) {
                base = Path.of(from)
                        .resolveSibling(specifier)
                        .normalize()
                        .toString()
                        .replace('\\', '/');
            } else {
                return null;
            }
            for (final String candidate : List.of(base + ".ts", base + ".tsx", base + "/index.ts", base)) {
                if (sources.containsKey(candidate)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    /** The texts each route of Steward answers with, read from the compiled classes. */
    private static final class Server {

        private record Ref(String owner, String name, String type) {}

        private final Map<Ref, List<Ref>> calls = new HashMap<>();

        private final Map<String, Set<String>> subtypes = new HashMap<>();

        private final Map<String, Set<Ref>> declared = new HashMap<>();

        private final Map<String, List<Ref>> routes = new LinkedHashMap<>();

        /** Each route's path, Javalin's {@code {name}} as {@code {}}, to the keys of Steward's texts it may answer. */
        static Map<String, Set<String>> answers() {
            final Server server = new Server();
            final Map<Ref, String> texts = texts();
            final Map<String, Set<String>> answers = new LinkedHashMap<>();
            server.routes.forEach((path, handlers) -> {
                final Set<String> keys = answers.computeIfAbsent(path, p -> new TreeSet<>());
                for (final Ref reached : server.reach(handlers)) {
                    final String key = texts.get(reached);
                    if (key != null) {
                        keys.add(key);
                    }
                }
            });
            return answers;
        }

        /** Whether a path the frontend names is this route: an interpolated segment stands for any. */
        static boolean matches(final String route, final String path) {
            if (route.equals("*")) {
                return true;
            }
            final String[] want = route.split("/", -1);
            final String[] have = path.split("/", -1);
            if (have.length > 0 && have[have.length - 1].equals("{}") && have.length <= want.length) {
                // A path that ends in an interpolation may name several segments in it.
                return prefix(want, have, have.length - 1);
            }
            return want.length == have.length && prefix(want, have, want.length);
        }

        private static boolean prefix(final String[] want, final String[] have, final int count) {
            for (int at = 0; at < count; at++) {
                if (!want[at].equals("{}") && !have[at].contains("{}") && !want[at].equals(have[at])) {
                    return false;
                }
            }
            return true;
        }

        private Server() {
            final Path root;
            try {
                root = Path.of(StewardTexts.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI());
            } catch (final URISyntaxException e) {
                throw new IllegalStateException(e);
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (final Path file :
                        files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    read(ClassFile.of().parse(file));
                }
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void read(final ClassModel model) {
            final String owner = model.thisClass().asInternalName();
            model.superclass()
                    .ifPresent(parent -> subtypes.computeIfAbsent(parent.asInternalName(), p -> new HashSet<>())
                            .add(owner));
            model.interfaces()
                    .forEach(parent -> subtypes.computeIfAbsent(parent.asInternalName(), p -> new HashSet<>())
                            .add(owner));
            for (final MethodModel method : model.methods()) {
                final Ref self = new Ref(
                        owner,
                        method.methodName().stringValue(),
                        method.methodType().stringValue());
                declared.computeIfAbsent(owner, o -> new HashSet<>()).add(self);
                final List<Ref> out = calls.computeIfAbsent(self, s -> new ArrayList<>());
                final List<Ref> handlers = new ArrayList<>();
                String path = null;
                for (final var element :
                        method.code().map(code -> code.elementList()).orElse(List.of())) {
                    if (!(element instanceof Instruction instruction)) {
                        continue;
                    }
                    if (instruction instanceof ConstantInstruction.LoadConstantInstruction load
                            && load.constantEntry() instanceof StringEntry string
                            && (string.stringValue().startsWith("/api/")
                                    || string.stringValue().startsWith("/auth/"))) {
                        path = string.stringValue().replaceAll("\\{[^}]+}", "{}");
                        handlers.clear();
                    } else if (instruction instanceof InvokeDynamicInstruction dynamic) {
                        for (final var argument : dynamic.bootstrapArgs()) {
                            if (argument instanceof java.lang.constant.DirectMethodHandleDesc handle) {
                                final Ref target = new Ref(
                                        handle.owner().descriptorString().replaceAll("^L|;$", ""),
                                        handle.methodName(),
                                        handle.lookupDescriptor());
                                out.add(target);
                                handlers.add(target);
                            }
                        }
                    } else if (instruction instanceof InvokeInstruction invoke) {
                        final Ref target = new Ref(
                                invoke.owner().asInternalName(),
                                invoke.name().stringValue(),
                                invoke.type().stringValue());
                        out.add(target);
                        if (GUARDS.contains(target.name()) && target.owner().startsWith("io/javalin/")) {
                            routes.computeIfAbsent("*", p -> new ArrayList<>()).addAll(handlers);
                            handlers.clear();
                        } else if (path != null
                                && REGISTERS.contains(target.name())
                                && target.owner().startsWith("io/javalin/")) {
                            routes.computeIfAbsent(path, p -> new ArrayList<>()).addAll(handlers);
                            path = null;
                        }
                    }
                }
            }
        }

        /** Every method a call from the handlers may run, an interface's or a parent's call running each override. */
        private Set<Ref> reach(final List<Ref> handlers) {
            final Set<Ref> seen = new HashSet<>();
            final ArrayDeque<Ref> left = new ArrayDeque<>(handlers);
            while (!left.isEmpty()) {
                final Ref ref = left.pop();
                if (!seen.add(ref)) {
                    continue;
                }
                for (final String owner : below(ref.owner())) {
                    final Ref own = new Ref(owner, ref.name(), ref.type());
                    if (declared.getOrDefault(owner, Set.of()).contains(own)) {
                        left.addAll(calls.getOrDefault(own, List.of()));
                    }
                }
            }
            return seen;
        }

        private Set<String> below(final String owner) {
            final Set<String> all = new LinkedHashSet<>(List.of(owner));
            final ArrayDeque<String> left = new ArrayDeque<>(List.of(owner));
            while (!left.isEmpty()) {
                for (final String sub : subtypes.getOrDefault(left.pop(), Set.of())) {
                    if (all.add(sub)) {
                        left.push(sub);
                    }
                }
            }
            return all;
        }

        /** Each method of Steward's spec that names a text, to its key, asked of the spec's own instance. */
        private static Map<Ref, String> texts() {
            final Map<Ref, String> texts = new HashMap<>();
            final ArrayDeque<Object> left = new ArrayDeque<>(List.of(StewardTexts.TEXTS));
            while (!left.isEmpty()) {
                final Object section = left.pop();
                for (final Class<?> type : section.getClass().getInterfaces()) {
                    for (final Method method : type.getMethods()) {
                        if (method.isDefault() || java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                            continue;
                        }
                        final Object[] args = new Object[method.getParameterCount()];
                        final Class<?>[] parameters = method.getParameterTypes();
                        for (int at = 0; at < args.length; at++) {
                            // An array of the type holds its default: zero, false or null.
                            args[at] = java.lang.reflect.Array.get(
                                    java.lang.reflect.Array.newInstance(parameters[at], 1), 0);
                        }
                        final Object result;
                        try {
                            result = method.invoke(section, args);
                        } catch (final ReflectiveOperationException e) {
                            throw new IllegalStateException(method.toString(), e);
                        }
                        if (result instanceof MessageRef ref) {
                            texts.put(
                                    new Ref(
                                            method.getDeclaringClass().getName().replace('.', '/'),
                                            method.getName(),
                                            java.lang.invoke.MethodType.methodType(method.getReturnType(), parameters)
                                                    .toMethodDescriptorString()),
                                    ref.key());
                        } else if (result != null && Proxy.isProxyClass(result.getClass())) {
                            left.push(result);
                        }
                    }
                }
            }
            return texts;
        }
    }

    public static void main(final String[] args) {
        try {
            Files.writeString(TARGET, render(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
