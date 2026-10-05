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
 *   - de-Lombok: @SneakyThrows replaced with explicit try/catch
 */
package eu.nordtal.jcore.config.spec;

import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.file.Path;
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

    /**
     * Tests whether the given class is a spec interface or not
     *
     * @param cl The class to check for
     * @return true if it's a spec
     */
    public static boolean isConfigSpec(final Class<?> cl) {
        return cl.isInterface() && cl.isAnnotationPresent(ConfigSpec.class);
    }

    /**
     * Generates a {@link SpecReference} for the specified config spec interface.
     *
     * A reference offers a flexible wrapper around the config spec value.
     *
     * @param type   The interface type
     * @param config The config file
     * @param <T>    The type
     * @return The newly created {@link SpecReference}.
     */
    public static <T> SpecReference<T> reference(final Class<T> type, final CommentedConfiguration config) {
        if (!isConfigSpec(type)) throw new IllegalArgumentException(type + " must be a spec class!");
        return new SpecReference<>(type, config);
    }

    /**
     * Generates a config spec from the specified config.
     *
     * @param type   The interface type
     * @param config The config file
     * @param <T>    The type
     * @return The newly created config spec.
     */
    public static <T> T fromConfig(final Class<T> type, final CommentedConfiguration config) {
        return reference(type, config).get();
    }

    /**
     * Generates a config spec from the specified file.
     *
     * @param type   The interface type
     * @param config The config file
     * @param <T>    The type
     * @return The newly created config spec.
     */
    public static <T> T fromFile(final Class<T> type, final Path config) {
        return reference(type, CommentedConfiguration.from(config)).get();
    }

    /**
     * Generates a config spec from the specified file.
     *
     * @param type   The interface type
     * @param config The config file
     * @param <T>    The type
     * @return The newly created config spec.
     */
    public static <T> T fromFile(final Class<T> type, final File config) {
        return reference(type, CommentedConfiguration.from(config.toPath())).get();
    }

    /**
     * Loads or generates (if necessary) all the information needed for the given spec
     *
     * @param interfaceType The spec type
     * @return The generated {@link SpecClass}
     */
    public static SpecClass from(final Class<?> interfaceType) {
        if (!interfaceType.isInterface()) throw new IllegalArgumentException("Class is not an interface.");
        if (!interfaceType.isAnnotationPresent(ConfigSpec.class))
            throw new IllegalArgumentException("Interface must have @ConfigSpec");

        return IMPLEMENTATIONS.computeIfAbsent(interfaceType, SpecClass::from);
    }

    /**
     * Creates a spec with the default values for the given spec type.
     *
     * If the spec contains other nested specs, they will be generated with their
     * default values as well.
     *
     * Lists, maps, sets, arrays, and primitive types will be initialized to
     * empty values. Everything else will be null.
     *
     * @param interfaceType The spec interface
     * @param <T>           The spec type
     * @return The newly created instance.
     */
    public static <T> T createDefault(final Class<T> interfaceType) {
        if (!isConfigSpec(interfaceType)) throw new IllegalArgumentException(interfaceType + " must be a spec class!");
        final Map<String, Object> properties = new LinkedHashMap<>();
        final T proxy = MapProxy.generate(interfaceType, properties);
        createDefaultMap(interfaceType, proxy, properties);
        return proxy;
    }

    /**
     * Creates a spec with the given values. Default values are not applied; the caller must guarantee those.
     *
     * @param interfaceType The spec interface
     * @param <T>           The spec type
     * @param properties    The values to seed the instance with
     * @return The newly created instance.
     */
    public static <T> T createUnsafe(final Class<T> interfaceType, final Map<String, Object> properties) {
        return MapProxy.generate(interfaceType, properties);
    }

    /**
     * The internal map of the given spec. Modifying this map immediately modifies the spec.
     *
     * @param configSpec the spec instance to unwrap
     * @return The internal map
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

    /** Invokes the interface's own default method to read the default value of {@code property}. */
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

    /** The empty/zero value a property without its own default is initialised to. */
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
