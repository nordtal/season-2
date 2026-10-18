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
 * Vendored from io.github.revxrsal:spec:1.5
 * (https://github.com/Revxrsal/spec, sources jar from repo1.maven.org). The MIT licence
 * and copyright notice above belong to the original author and are retained as the licence
 * requires. See NOTICE for the full third-party licence text.
 *
 * Modified by nordtal.eu:
 *   - package revxrsal.spec -> eu.nordtal.season.spec
 *   - de-Lombok: @SneakyThrows replaced with explicit try/catch
 *   - reading from a file removed; gsonBuilder added
 *   - doc comments shortened to this repository's conventions
 */
package eu.nordtal.season.spec;

import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** Entry points for creating, inspecting and reflecting on {@code @ConfigSpec} interfaces. */
public final class Specs {

    private static final Map<Class<?>, SpecClass> IMPLEMENTATIONS = new ConcurrentHashMap<>();

    public static boolean isConfigSpec(final Class<?> cl) {
        return cl.isInterface() && cl.isAnnotationPresent(ConfigSpec.class);
    }

    /** Returns a Gson builder that reads and writes spec interfaces and keeps whole numbers as {@code long}. */
    public static GsonBuilder gsonBuilder() {
        return new GsonBuilder()
                .registerTypeAdapterFactory(SpecAdapterFactory.INSTANCE)
                .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE);
    }

    public static SpecClass from(final Class<?> interfaceType) {
        if (!interfaceType.isInterface()) throw new IllegalArgumentException("Class is not an interface.");
        if (!interfaceType.isAnnotationPresent(ConfigSpec.class))
            throw new IllegalArgumentException("Interface must have @ConfigSpec");

        return IMPLEMENTATIONS.computeIfAbsent(interfaceType, SpecClass::from);
    }

    /**
     * Creates a spec with the default values of its type, nested specs included.
     *
     * @param interfaceType the spec interface
     * @param <T>           the spec type
     * @return the new instance. A property without a default starts empty for a collection, zero for a primitive and
     * {@code null} otherwise
     */
    public static <T> T createDefault(final Class<T> interfaceType) {
        if (!isConfigSpec(interfaceType)) throw new IllegalArgumentException(interfaceType + " must be a spec class!");
        final Map<String, Object> properties = new LinkedHashMap<>();
        final T proxy = MapProxy.generate(interfaceType, properties);
        createDefaultMap(interfaceType, proxy, properties);
        return proxy;
    }

    /**
     * Creates a spec with the given values. It applies no defaults, so the caller supplies them.
     *
     * @param interfaceType the spec interface
     * @param <T>           the spec type
     * @param properties    the values of the instance
     * @return the new instance
     */
    public static <T> T createUnsafe(final Class<T> interfaceType, final Map<String, Object> properties) {
        return MapProxy.generate(interfaceType, properties);
    }

    /**
     * Returns the internal map of a spec. A change to the map changes the spec.
     *
     * @param configSpec the spec instance to unwrap
     * @return the internal map
     */
    public static Map<String, Object> getInternalMap(final Object configSpec) {
        return MapProxy.getInternalMap(configSpec);
    }

    static <T> void createDefaultMap(
            final Class<T> interfaceType, final T proxy, final Map<String, Object> properties) {
        final SpecClass specClass = from(interfaceType);
        for (final SpecProperty value : specClass.properties().values()) {
            if (value.isHandledByProxy()) continue;
            if (value.hasDefault()) {
                properties.put(value.key(), invokeDefaultMethod(interfaceType, proxy, value));
            } else {
                properties.put(value.key(), defaultValueFor(value.type()));
            }
        }
    }

    /** Calls the default method of the interface to read the default value of {@code property}. */
    private static <T> Object invokeDefaultMethod(
            final Class<T> interfaceType, final T proxy, final SpecProperty property) {
        final Method getter = property.getter();
        final MethodHandle getterHandle;
        try {
            getterHandle =
                    MHLookup.privateLookupIn(interfaceType).in(interfaceType).unreflectSpecial(getter, interfaceType);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Cannot access the default method for property '" + property.key() + "' on "
                            + interfaceType.getName(),
                    e);
        }
        try {
            return getterHandle.invoke(proxy);
        } catch (Throwable e) {
            throw new IllegalStateException(
                    "Failed to read the default value of property '" + property.key() + "' on "
                            + interfaceType.getName(),
                    e);
        }
    }

    /** Returns the empty or zero value of a property without a default. */
    private static @Nullable Object defaultValueFor(final Class<?> type) {
        if (isConfigSpec(type)) {
            return createDefault(type);
        } else if (type == List.class || type == Iterable.class || type == Collection.class) {
            return new ArrayList<>();
        } else if (type == Set.class) {
            return new LinkedHashSet<>();
        } else if (type == Map.class) {
            return new LinkedHashMap<>();
        } else if (type.isArray()) {
            return Array.newInstance(type.getComponentType(), 0);
        } else if (type == boolean.class) {
            return false;
        } else if (type == byte.class) {
            return (byte) 0;
        } else if (type == char.class) {
            return '\u0000';
        } else if (type == short.class) {
            return (short) 0;
        } else if (type == int.class) {
            return 0;
        } else if (type == long.class) {
            return 0L;
        } else if (type == float.class) {
            return 0.0f;
        } else if (type == double.class) {
            return 0.0d;
        } else {
            return null;
        }
    }
}
