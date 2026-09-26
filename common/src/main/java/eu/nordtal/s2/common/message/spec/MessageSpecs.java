package eu.nordtal.s2.common.message.spec;

import eu.nordtal.s2.common.message.MessageRef;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a {@link MessageSpec} interface into an object whose methods return filled
 * {@link MessageRef}s.
 *
 * <pre>
 * SmpMessages m = MessageSpecs.create(SmpMessages.class);
 * renderer.format(locale, m.duel().won(opponent));   // smp.duel.won, {opponent}
 * </pre>
 *
 * <p>A {@code default} method runs as written, which is where a spec maps an enum or a config
 * value onto one of its own methods - the one place a key used to be glued together from parts.</p>
 */
public final class MessageSpecs {

    private MessageSpecs() {}

    /**
     * @param spec an interface annotated {@link MessageSpec}
     * @return the spec's messages
     * @throws IllegalArgumentException if {@code spec} is not such an interface
     */
    public static <T> T create(final Class<T> spec) {
        if (!spec.isInterface() || !spec.isAnnotationPresent(MessageSpec.class)) {
            throw new IllegalArgumentException(spec.getName() + " is not a @MessageSpec interface");
        }
        return section(spec, "");
    }

    private static <T> T section(final Class<T> type, final String prefix) {
        final Object proxy =
                Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, new Handler(type, prefix));
        return type.cast(proxy);
    }

    /** The key segment a method contributes: {@link Key}, or its name in kebab case. */
    static String segment(final Method method) {
        final Key key = method.getAnnotation(Key.class);
        return key != null ? key.value() : kebab(method.getName());
    }

    /** {@code noSuchMember} to {@code no-such-member}; a digit run is its own word. */
    static String kebab(final String name) {
        final StringBuilder out = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            final boolean boundary = i > 0
                    && (Character.isUpperCase(c) || Character.isDigit(c) != Character.isDigit(name.charAt(i - 1)));
            if (boundary) {
                out.append('-');
            }
            out.append(Character.toLowerCase(c));
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /** Whether a method is a key rather than a section or a helper. */
    static boolean isKey(final Method method) {
        return !method.isDefault() && method.getReturnType() == MessageRef.class;
    }

    /** Whether a method opens a section. */
    static boolean isSection(final Method method) {
        return !method.isDefault()
                && method.getParameterCount() == 0
                && method.getReturnType().isInterface()
                && method.getReturnType() != MessageRef.class;
    }

    /** The placeholder names of a key method, in parameter order. */
    static List<String> args(final Method method) {
        final List<String> names = new ArrayList<>();
        for (final Parameter parameter : method.getParameters()) {
            final Arg arg = parameter.getAnnotation(Arg.class);
            if (arg == null) {
                throw new IllegalStateException(method + ": parameter " + parameter.getName()
                        + " has no @Arg, so there is no placeholder it fills");
            }
            names.add(arg.value());
        }
        return names;
    }

    private static final class Handler implements InvocationHandler {

        private final Class<?> type;
        private final String prefix;
        private final Map<Method, Object> sections = new ConcurrentHashMap<>();
        private final Map<Method, List<String>> argNames = new ConcurrentHashMap<>();

        Handler(final Class<?> type, final String prefix) {
            this.type = type;
            this.prefix = prefix;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> type.getSimpleName() + "[" + prefix + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.toString());
                };
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            if (isSection(method)) {
                return sections.computeIfAbsent(method, m -> section(m.getReturnType(), prefix + segment(m) + "."));
            }
            if (isKey(method)) {
                final List<String> names = argNames.computeIfAbsent(method, MessageSpecs::args);
                final Map<String, Object> values = new LinkedHashMap<>();
                for (int i = 0; i < names.size(); i++) {
                    values.put(names.get(i), args[i]);
                }
                return new MessageRef(prefix + segment(method), values);
            }
            throw new UnsupportedOperationException(method + " is neither a message nor a section");
        }
    }
}
