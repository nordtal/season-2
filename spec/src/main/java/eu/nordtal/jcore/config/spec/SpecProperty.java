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
 *   - de-Lombok: @RequiredArgsConstructor / @Getter replaced with explicit members
 */
package eu.nordtal.jcore.config.spec;

import static eu.nordtal.jcore.config.spec.CommentedConfiguration.NEW_LINE;
import static java.util.stream.Collectors.toList;

import com.google.gson.annotations.SerializedName;
import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.HandledByProxy;
import eu.nordtal.jcore.config.spec.annotation.IgnoreMethod;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A property in a {@link ConfigSpec}. */
public final class SpecProperty {

    /** The key of the property, set by {@link Key @Key}. */
    private final String key;

    SpecProperty(final String key) {
        this.key = key;
    }

    private @Nullable Class<?> type;

    private @Nullable Method getter;

    /** The property setter method, or {@code null} if the property has none. */
    private @Nullable Method setter;

    /** The comments on this property, set by {@link Comment @Comment}. */
    private List<String> comments = Collections.emptyList();

    /**
     * Whether this property is handled by the proxy rather than being an actual property. See {@link HandledByProxy}.
     */
    private boolean isHandledByProxy;

    /**
     * Tests whether this property is handled by the proxy rather than being an actual configuration property.
     *
     * @return if the property is proxy-handled
     */
    public boolean isHandledByProxy() {
        return isHandledByProxy;
    }

    /**
     * The key of the property: {@link Key @Key} or {@link SerializedName @SerializedName}, else the stripped name.
     *
     * @return the property's key
     */
    public String key() {
        return key;
    }

    /**
     * The getter method of this property.
     *
     * @return the getter method
     */
    public Method getter() {
        return Objects.requireNonNull(getter, "getter is set before a property is exposed");
    }

    /**
     * Whether this property has a default value.
     *
     * @return {@code true} if this property has a default value
     */
    public boolean hasDefault() {
        return getter().isDefault();
    }

    /**
     * The setter of this property, or {@code null} if it declares none.
     *
     * @return the setter method, or {@code null}
     */
    public @Nullable Method setter() {
        return setter;
    }

    /**
     * The comments on this property, set by {@link Comment @Comment}.
     *
     * @return the comments on this property
     */
    public List<String> comments() {
        return comments;
    }

    /**
     * Whether this property has any comments on it.
     *
     * @return {@code true} if this property has any comments on it
     */
    public boolean hasComments() {
        return !comments.isEmpty();
    }

    /**
     * Sets the type of this property, checking that it does not conflict with a type already set.
     */
    private void setType(final Class<?> type) {
        if (this.type == null) {
            this.type = type;
        } else if (!this.type.equals(type)) {
            throw new IllegalArgumentException(
                    "Inconsistent types for property " + key + ". Received " + this.type + " and " + type + ".");
        }
    }

    /**
     * Whether the method name starts with {@code set}.
     *
     * @param method the method to check
     * @return {@code true} if its name starts with {@code set}
     */
    public static boolean impliesSetter(final Method method) {
        return method.getName().startsWith("set");
    }

    /**
     * The property key for {@code method}: its {@code @Key} or {@code @SerializedName}, or the stripped name.
     *
     * @param method the method to derive the key from
     * @return the property key
     */
    public static String keyOf(final Method method) {
        final Key key = method.getAnnotation(Key.class);
        if (key != null) {
            return key.value();
        }
        final SerializedName sn = method.getAnnotation(SerializedName.class);
        if (sn != null) {
            return sn.value();
        }
        return fromName(method.getName());
    }

    /** Strips a get-, is- or set- prefix from {@code name} if it has one. */
    private static String fromName(final String name) {
        if (name.startsWith("get") || name.startsWith("set")) {
            return lowerFirst(name.substring(3));
        } else if (name.startsWith("is")) {
            return lowerFirst(name.substring(2));
        }
        return name;
    }

    /** Lower-cases the first character of {@code name}. */
    private static String lowerFirst(final String name) {
        if (name.isEmpty()) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    static Map<String, SpecProperty> propertiesOf(final Class<?> interfaceType) {
        Objects.requireNonNull(interfaceType, "interface cannot be null!");
        if (!interfaceType.isInterface()) {
            throw new IllegalArgumentException("Class is not an interface: " + interfaceType.getName());
        }
        if (!interfaceType.isAnnotationPresent(ConfigSpec.class)) {
            throw new IllegalArgumentException("Interface does not have @ConfigSpec on it!");
        }
        final Map<String, SpecProperty> properties = new LinkedHashMap<>();
        final Method[] methods = interfaceType.getMethods();

        sortByAnnotation(methods);
        for (final Method method : methods) {
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.isAnnotationPresent(IgnoreMethod.class)) {
                if (method.isDefault()) {
                    continue;
                } else {
                    throw new IllegalArgumentException(
                            "Cannot ignore a non-default method! Ignored methods must be default");
                }
            }
            parse(method, properties);
        }
        for (final SpecProperty value : properties.values()) {
            if (value.type == null) {
                throw new IllegalArgumentException("Failed to infer the type of property '" + value.key + "'!");
            }
            if (value.getter == null) {
                throw new IllegalArgumentException("No getter exists for property '" + value.key + "'!");
            }
        }
        return Collections.unmodifiableMap(properties);
    }

    private static <T extends AnnotatedElement> void sortByAnnotation(final T[] methods) {
        Arrays.sort(methods, (o1, o2) -> {
            final Order order1 = o1.getAnnotation(Order.class);
            final Order order2 = o2.getAnnotation(Order.class);
            if (order1 == null && order2 == null) {
                return 0; // Both methods are unannotated.
            }
            if (order1 == null) {
                return -1; // o1 is unannotated, so it comes first.
            }
            if (order2 == null) {
                return 1; // o2 is unannotated, so it comes first.
            }
            return Integer.compare(order1.value(), order2.value());
        });
    }

    private static void parse(final Method method, final Map<String, SpecProperty> properties) {
        final String key = keyOf(method);
        final SpecProperty existing = properties.computeIfAbsent(key, SpecProperty::new);
        final @Nullable List<String> comments = commentsOf(method);
        if (Arrays.stream(method.getAnnotations()).anyMatch(SpecProperty::isHandledByProxy)) {
            existing.isHandledByProxy = true;
            existing.getter = method;
            existing.setType(method.getReturnType());
            return;
        }
        if (comments != null) {
            if (existing.comments.isEmpty()) {
                existing.comments = comments;
            } else {
                throw new IllegalArgumentException("Inconsistent comments for property '" + key + "'");
            }
        }
        if (method.getReturnType() == Void.TYPE || impliesSetter(method)) {
            if (existing.setter != null) {
                throw new IllegalArgumentException("Found 2 setters for property '" + key + "'!");
            }
            if (method.getReturnType() != Void.TYPE) {
                throw new IllegalArgumentException("Setter for property '" + key + "' must return void!");
            }
            if (method.getParameterCount() == 0) {
                throw new IllegalArgumentException("Setter for property '" + key + "' has no parameters!");
            }
            if (method.getParameterCount() > 1) {
                throw new IllegalArgumentException("Setter for property '" + key + "' has more than 1 parameter!");
            }
            existing.setType(method.getParameterTypes()[0]);
            existing.setter = method;
        } else {
            if (existing.getter != null) {
                throw new IllegalArgumentException("Found 2 getters for property '" + key + "'!");
            }
            if (method.getParameterCount() != 0) {
                throw new IllegalArgumentException("Getter for property '" + key + "' cannot take parameters!");
            }
            existing.setType(method.getReturnType());
            existing.getter = method;
        }
    }

    private static boolean isHandledByProxy(final Annotation annotation) {
        return annotation.annotationType().isAnnotationPresent(HandledByProxy.class);
    }

    private static @Nullable List<String> commentsOf(final Method method) {
        final Comment comment = method.getAnnotation(Comment.class);
        if (comment != null) {
            final String[] value = comment.value();
            return Arrays.stream(value).flatMap(NEW_LINE::splitAsStream).collect(toList());
        }
        return null;
    }

    /**
     * The header comment declared on {@code type} by {@link ConfigSpec#header()}.
     *
     * @param type the spec interface to read the header off
     * @return the header lines, or an empty list if none is declared
     */
    public static List<String> headerOf(final Class<?> type) {
        final ConfigSpec spec = type.getAnnotation(ConfigSpec.class);
        if (spec != null) {
            final String[] value = spec.header();
            return Arrays.stream(value).flatMap(NEW_LINE::splitAsStream).collect(toList());
        }
        return Collections.emptyList();
    }

    @Override
    public String toString() {
        return "SpecProperty(key='" + key + "')";
    }

    /**
     * The type of this property, as declared by its getter or setter.
     *
     * @return the property's type
     */
    public Class<?> type() {
        return Objects.requireNonNull(type, "type is set before a property is exposed");
    }
}
