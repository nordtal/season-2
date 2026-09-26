/*
 * This file is part of Cream, licensed under the MIT License.
 *
 *  Copyright (c) Revxrsal <reflxction.github@gmail.com>
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */
/*
 * Vendored into jcore from io.github.revxrsal:spec:1.5
 * (https://github.com/Revxrsal/spec, sources jar from repo1.maven.org). The MIT licence
 * and copyright notice above belong to the original author and are retained as the licence
 * requires. See NOTICE for the full third-party licence text.
 *
 * Modified by nordtal.eu:
 *   - package revxrsal.spec -> eu.nordtal.jcore.config.spec
 *   - de-Lombok: @SneakyThrows replaced with explicit handling; the sneaky-throw
 *   -      helper now uses the plain generic-cast idiom
 */
package eu.nordtal.jcore.config.spec;

import static eu.nordtal.jcore.config.spec.SpecProperty.impliesSetter;
import static eu.nordtal.jcore.config.spec.SpecProperty.keyOf;
import static eu.nordtal.jcore.config.spec.Specs.createDefaultMap;
import static eu.nordtal.jcore.config.spec.Specs.isConfigSpec;

import eu.nordtal.jcore.config.spec.annotation.AsMap;
import eu.nordtal.jcore.config.spec.annotation.IgnoreMethod;
import eu.nordtal.jcore.config.spec.annotation.Memoize;
import eu.nordtal.jcore.config.spec.annotation.Reload;
import eu.nordtal.jcore.config.spec.annotation.Reset;
import eu.nordtal.jcore.config.spec.annotation.Save;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Generates proxies that are backed by {@link Map maps}.
 */
final class MapProxy<T> implements InvocationHandler {

    @SuppressWarnings("unchecked")
    public static <T> T generate(final Class<T> type, final Map<String, Object> map) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[] {type}, new MapProxy<>(type, map));
    }

    @SuppressWarnings({"unchecked"})
    static <T> Map<String, Object> getInternalMap(final T value) {
        Objects.requireNonNull(value, "value is null!");
        if (!Proxy.isProxyClass(value.getClass())) {
            for (final Class<?> cInterface : value.getClass().getInterfaces()) {
                if (isConfigSpec(cInterface))
                    throw new IllegalArgumentException("Don't try to create an instance of a ConfigSpec directly! "
                            + "Use Specs.createDefault() or Specs.createUnsafe() instead. "
                            + "Tried to create an instance of " + cInterface + ".");
            }
            throw new IllegalArgumentException("Not a proxy instance: " + value);
        }
        final InvocationHandler handler = Proxy.getInvocationHandler(value);
        if (!(handler instanceof MapProxy)) {
            throw new IllegalArgumentException(
                    "Not a config spec: " + value + " (proxy is handled by " + handler + ")");
        }
        return ((MapProxy<T>) handler).map;
    }

    private static final Method TO_STRING;
    private static final Method EQUALS;
    private static final Method HASH_CODE;

    private final Class<T> type;
    private final Map<String, Object> map;

    private @Nullable Map<Method, MethodHandle> defaultMethodHandles;
    private @Nullable Map<Method, Object> memoized;

    /** Wraps an {@code invoke()} result that may itself legitimately be {@code null}, distinct from "did not apply". */
    private record Handled(@Nullable Object value) {}

    public MapProxy(final Class<T> type, final Map<String, Object> map) {
        this.type = type;
        this.map = map;
    }

    @Override
    public @Nullable Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
        if (method.equals(TO_STRING)) {
            return generateToString();
        }
        if (method.equals(EQUALS)) {
            final @Nullable Handled equalsResult = equalsResult(args);
            if (equalsResult != null) {
                return equalsResult.value();
            }
        }
        if (method.equals(HASH_CODE)) {
            return map.hashCode();
        }
        final @Nullable Handled annotationResult = handleAnnotation(proxy, method, args);
        if (annotationResult != null) {
            return annotationResult.value();
        }
        final String key = keyOf(method);
        if (method.getReturnType() == Void.TYPE || impliesSetter(method)) {
            map.put(key, args[0]);
            if (memoized != null) memoized.clear();
            return null;
        } else {
            return map.get(key);
        }
    }

    /** The {@code equals(Object)} case of {@link #invoke}; {@code null} means fall through to the caller. */
    private @Nullable Handled equalsResult(final Object[] args) {
        final Object other = args[0];
        if (!Proxy.isProxyClass(other.getClass())) {
            return new Handled(false);
        }
        if (isConfigSpec(other.getClass())) {
            final MapProxy<?> otherHandler = (MapProxy<?>) Proxy.getInvocationHandler(args);
            return new Handled(map.equals(otherHandler.map));
        }
        return null;
    }

    /** The annotation-driven special cases of {@link #invoke}; a {@code null} return means none applied. */
    private @Nullable Handled handleAnnotation(final Object proxy, final Method method, final Object[] args)
            throws Throwable {
        if (method.isAnnotationPresent(IgnoreMethod.class)) {
            return new Handled(asMethodHandle(method).bindTo(proxy).invokeWithArguments(args));
        }
        if (method.isAnnotationPresent(Memoize.class)) {
            if (!method.isDefault()) throw new IllegalArgumentException("@Memoize methods must be default!");
            if (memoized == null) memoized = new ConcurrentHashMap<>();
            return new Handled(memoized.computeIfAbsent(method, m -> {
                try {
                    return asMethodHandle(m).bindTo(proxy).invokeWithArguments(args);
                } catch (Throwable e) {
                    sneakyThrow(e);
                    return null;
                }
            }));
        }
        if (method.isAnnotationPresent(AsMap.class)) {
            final AsMap asMap = method.getAnnotation(AsMap.class);
            return new Handled(
                    switch (Objects.requireNonNull(asMap).value()) {
                        case CLONE -> new LinkedHashMap<>(map);
                        case IMMUTABLE_VIEW -> Collections.unmodifiableMap(map);
                        case UNDERLYING_MAP -> map;
                    });
        }
        if (method.isAnnotationPresent(Reload.class)) {
            throw new IllegalStateException("You cannot reload this! Try to reload the top entity.");
        }
        if (method.isAnnotationPresent(Save.class)) {
            throw new IllegalStateException("You cannot save this! Try to save the top entity.");
        }
        if (method.isAnnotationPresent(Reset.class)) {
            this.map.clear();
            if (memoized != null) memoized.clear();
            //noinspection unchecked
            createDefaultMap(type, (T) proxy, this.map);
            return new Handled(null);
        }
        return null;
    }

    private MethodHandle asMethodHandle(final Method m) {
        if (defaultMethodHandles == null) defaultMethodHandles = new HashMap<>();
        MethodHandle mh = defaultMethodHandles.get(m);
        if (mh == null) {
            try {
                mh = MHLookup.privateLookupIn(type).in(type).unreflectSpecial(m, type);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot access default method " + m, e);
            }
            defaultMethodHandles.put(m, mh);
        }
        return mh;
    }

    private String generateToString() {
        final StringBuilder sb = new StringBuilder(type.getSimpleName() + "(");
        final Iterator<Map.Entry<String, Object>> it = map.entrySet().iterator();

        while (it.hasNext()) {
            final Map.Entry<String, Object> entry = it.next();
            sb.append(entry.getKey()).append("=").append(entry.getValue());
            if (it.hasNext()) {
                sb.append(", ");
            }
        }

        sb.append(")");
        return sb.toString();
    }

    static {
        try {
            TO_STRING = Object.class.getDeclaredMethod("toString");
            EQUALS = Object.class.getDeclaredMethod("equals", Object.class);
            HASH_CODE = Object.class.getDeclaredMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private static RuntimeException sneakyThrow(final Throwable t) {
        if (t == null) throw new NullPointerException("t");
        return sneakyThrow0(t);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow0(final Throwable t) throws T {
        throw (T) t;
    }
}
