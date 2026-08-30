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
 * Vendored into jcore from io.github.revxrsal:spec:1.5 on 2026-08-30
 * (https://github.com/Revxrsal/spec, sources jar from repo1.maven.org). The MIT licence
 * and copyright notice above belong to the original author and are retained as the licence
 * requires. See NOTICE for the full third-party licence text.
 *
 * Modified by nordtal.eu:
 *   - package revxrsal.spec -> eu.nordtal.jcore.config.spec
 *   - de-Lombok: @SneakyThrows replaced with explicit try/catch
 */
package eu.nordtal.jcore.config.spec;

import org.jetbrains.annotations.NotNull;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class Specs {

    private static final Map<Class<?>, SpecClass> IMPLEMENTATIONS = new ConcurrentHashMap<>();

    /**
     * Tests whether the given class is a spec interface or not
     *
     * @param cl The class to check for
     * @return true if it's a spec
     */
    public static boolean isConfigSpec(@NotNull Class<?> cl) {
        return cl.isInterface() && cl.isAnnotationPresent(ConfigSpec.class);
    }

    /**
     * Generates a {@link SpecReference} for the specified config spec interface.
     * <p>
     * A reference offers a flexible wrapper around the config spec value.
     *
     * @param type   The interface type
     * @param config The config file
     * @param <T>    The type
     * @return The newly created {@link SpecReference}.
     */
    public static @NotNull <T> SpecReference<T> reference(@NotNull Class<T> type, @NotNull CommentedConfiguration config) {
        if (!isConfigSpec(type))
            throw new IllegalArgumentException(type + " must be a spec class!");
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
    public static @NotNull <T> T fromConfig(@NotNull Class<T> type, @NotNull CommentedConfiguration config) {
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
    public static @NotNull <T> T fromFile(@NotNull Class<T> type, @NotNull Path config) {
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
    public static @NotNull <T> T fromFile(@NotNull Class<T> type, @NotNull File config) {
        return reference(type, CommentedConfiguration.from(config.toPath())).get();
    }

    /**
     * Loads or generates (if necessary) all the information needed for the
     * given spec
     *
     * @param interfaceType The spec type
     * @return The generated {@link SpecClass}
     */
    public static @NotNull SpecClass from(@NotNull Class<?> interfaceType) {
        if (!interfaceType.isInterface())
            throw new IllegalArgumentException("Class is not an interface.");
        if (!interfaceType.isAnnotationPresent(ConfigSpec.class))
            throw new IllegalArgumentException("Interface must have @ConfigSpec");

        return IMPLEMENTATIONS.computeIfAbsent(interfaceType, SpecClass::from);
    }

    /**
     * Creates a spec with the default values for the given spec type.
     * <p>
     * If the spec contains other nested specs, they will be generated with their
     * default values as well.
     * <p>
     * Lists, maps, sets, arrays, and primitive types will be initialized to
     * empty values. Everything else will be null.
     *
     * @param interfaceType The spec interface
     * @param <T>           The spec type
     * @return The newly created instance.
     */
    public static @NotNull <T> T createDefault(@NotNull Class<T> interfaceType) {
        if (!isConfigSpec(interfaceType))
            throw new IllegalArgumentException(interfaceType + " must be a spec class!");
        Map<String, Object> properties = new LinkedHashMap<>();
        T proxy = MapProxy.generate(interfaceType, properties);
        createDefaultMap(interfaceType, proxy, properties);
        return proxy;
    }

    /**
     * Creates a spec with the given values from the map. Note that this
     * method does not respect default values, so it is the user's responsibility
     * to guarantee those.
     *
     * @param interfaceType The spec interface
     * @param <T>           The spec type
     * @return The newly created instance.
     */
    public static @NotNull <T> T createUnsafe(
            @NotNull Class<T> interfaceType,
            @NotNull Map<String, Object> properties
    ) {
        return MapProxy.generate(interfaceType, properties);
    }

    /**
     * Returns the internal map of the given spec. Modifying this map
     * will immediately modify the spec, so be careful with it!
     *
     * @return The internal map
     */
    public static @NotNull Map<String, Object> getInternalMap(@NotNull Object configSpec) {
        return MapProxy.getInternalMap(configSpec);
    }

    static <T> void createDefaultMap(@NotNull Class<T> interfaceType, T proxy, @NotNull Map<String, Object> properties) {
        SpecClass specClass = from(interfaceType);
        for (SpecProperty value : specClass.properties().values()) {
            if (value.isHandledByProxy())
                continue;
            if (value.hasDefault()) {
                Method getter = value.getter();
                MethodHandle getterHandle;
                try {
                    getterHandle = MHLookup.privateLookupIn(interfaceType)
                            .in(interfaceType)
                            .unreflectSpecial(getter, interfaceType);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(
                            "Cannot access the default method for property '" + value.key()
                                    + "' on " + interfaceType.getName(), e);
                }
                try {
                    properties.put(value.key(), getterHandle.invoke(proxy));
                } catch (Throwable e) {
                    throw new IllegalStateException(
                            "Failed to read the default value of property '" + value.key()
                                    + "' on " + interfaceType.getName(), e);
                }
            } else {
                Class<?> type = value.type();
                if (isConfigSpec(type)) {
                    Object v = createDefault(type);
                    properties.put(value.key(), v);
                } else if (type == List.class
                        || type == Iterable.class
                        || type == Collection.class
                ) {
                    properties.put(value.key(), new ArrayList<>());
                } else if (type == Set.class) {
                    properties.put(value.key(), new LinkedHashSet<>());
                } else if (type == Map.class) {
                    properties.put(value.key(), new LinkedHashMap<>());
                } else if (type.isArray()) {
                    Object array = Array.newInstance(type.getComponentType(), 0);
                    properties.put(value.key(), array);
                } else if (type == boolean.class) {
                    properties.put(value.key(), false);
                } else if (type == byte.class) {
                    properties.put(value.key(), (byte) 0);
                } else if (type == char.class) {
                    properties.put(value.key(), '\u0000');
                } else if (type == short.class) {
                    properties.put(value.key(), (short) 0);
                } else if (type == int.class) {
                    properties.put(value.key(), 0);
                } else if (type == long.class) {
                    properties.put(value.key(), 0L);
                } else if (type == float.class) {
                    properties.put(value.key(), 0.0f);
                } else if (type == double.class) {
                    properties.put(value.key(), 0.0d);
                } else {
                    properties.put(value.key(), null);
                }
            }
        }
    }
}
