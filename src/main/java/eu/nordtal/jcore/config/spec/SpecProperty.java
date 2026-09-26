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
 *   - de-Lombok: @RequiredArgsConstructor / @Getter replaced with explicit members
 */
package eu.nordtal.jcore.config.spec;

import static eu.nordtal.jcore.config.spec.CommentedConfiguration.NEW_LINE;
import static java.util.stream.Collectors.toList;

import com.google.gson.annotations.SerializedName;
import eu.nordtal.jcore.config.spec.annotation.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Represents a property in a {@link ConfigSpec}
 */
public final class SpecProperty {

    /**
     * The key of the property, set by {@link Key @Key}
     */
    private final @NotNull String key;

    SpecProperty(@NotNull String key) {
        this.key = key;
    }

    /**
     * The property type
     */
    private Class<?> type;

    /**
     * The property getter method
     */
    private Method getter;

    /**
     * The property setter method. Could be null
     */
    private @Nullable Method setter;

    /**
     * The comments on this property
     */
    private @Unmodifiable List<String> comments = Collections.emptyList();

    /**
     * Tests whether is that property handled by the proxy, and does
     * not represent an actual property.
     * <p>
     * See {@link HandledByProxy}
     */
    private boolean isHandledByProxy;

    /**
     * Tests whether this property is handled by the proxy rather than being an
     * actual configuration property.
     *
     * @return if the property is proxy-handled
     */
    public boolean isHandledByProxy() {
        return isHandledByProxy;
    }

    /**
     * The key of the property, set by {@link Key @Key} or {@link SerializedName @SerializedName}.
     * <p>
     * If none of the given annotations is specified, it will use the field
     * name (strips is-, get- and set- prefixes)
     *
     * @return The property name
     */
    public @NotNull String key() {
        return key;
    }

    /**
     * Returns the getter method of this property
     *
     * @return The getter method
     */
    public @NotNull Method getter() {
        return getter;
    }

    /**
     * Tests whether this property has a default value or not
     *
     * @return if this property has a default value or not
     */
    public boolean hasDefault() {
        return getter.isDefault();
    }

    /**
     * Returns the setter of this property. Could be null if the
     * property does not define a setter.
     *
     * @return The property setter
     */
    public @Nullable Method setter() {
        return setter;
    }

    /**
     * Returns the comments on this property, specified by {@link Comment @Comment}.
     *
     * @return The comments
     */
    public @NotNull @Unmodifiable List<String> comments() {
        return comments;
    }

    /**
     * Tests if this property has any comments on it or not
     *
     * @return if the property has comments
     */
    public boolean hasComments() {
        return !comments.isEmpty();
    }

    /**
     * Sets the type of this property. This performs type checks to ensure
     * the two types do not conflict
     *
     * @param type The new type
     */
    private void setType(@Nullable Class<?> type) {
        if (this.type == null) this.type = type;
        else if (!this.type.equals(type))
            throw new IllegalArgumentException(
                    "Inconsistent types for property " + key + ". Received " + this.type + " and " + type + ".");
    }

    /**
     * Tests if the method implies a setter or not. This simply
     * checks if the method name starts with 'set'.
     *
     * @param method The method to check for
     * @return if it implies a setter
     */
    public static boolean impliesSetter(@NotNull Method method) {
        return method.getName().startsWith("set");
    }

    /**
     * Returns the name of the property that is represented
     * by this method
     *
     * @param method The method to get for
     * @return The property name
     */
    public static @NotNull String keyOf(@NotNull Method method) {
        Key key = method.getAnnotation(Key.class);
        if (key != null) return key.value();
        SerializedName sn = method.getAnnotation(SerializedName.class);
        if (sn != null) return sn.value();
        return fromName(method.getName());
    }

    /**
     * Strips get-, is- and set- prefixes from the given name if necessary.
     *
     * @param name The name to strip from
     * @return The new name
     */
    private static @NotNull String fromName(@NotNull String name) {
        if (name.startsWith("get") || name.startsWith("set")) return lowerFirst(name.substring(3));
        else if (name.startsWith("is")) return lowerFirst(name.substring(2));
        return name;
    }

    /**
     * Lower-cases the first letter of the given string
     *
     * @param name The string
     * @return The new string
     */
    private static @NotNull String lowerFirst(@NotNull String name) {
        if (name.isEmpty()) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    static @NotNull @Unmodifiable Map<String, SpecProperty> propertiesOf(@NotNull Class<?> interfaceType) {
        Objects.requireNonNull(interfaceType, "interface cannot be null!");
        if (!interfaceType.isInterface())
            throw new IllegalArgumentException("Class is not an interface: " + interfaceType.getName());
        if (!interfaceType.isAnnotationPresent(ConfigSpec.class))
            throw new IllegalArgumentException("Interface does not have @ConfigSpec on it!");
        Map<String, SpecProperty> properties = new LinkedHashMap<>();
        Method[] methods = interfaceType.getMethods();

        sortByAnnotation(methods);
        for (Method method : methods) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            if (method.isAnnotationPresent(IgnoreMethod.class)) {
                if (method.isDefault()) continue;
                else
                    throw new IllegalArgumentException(
                            "Cannot ignore a non-default method! Ignored methods must be default");
            }
            parse(method, properties);
        }
        for (SpecProperty value : properties.values()) {
            if (value.type == null)
                throw new IllegalArgumentException("Failed to infer the type of property '" + value.key + "'!");
            if (value.getter == null)
                throw new IllegalArgumentException("No getter exists for property '" + value.key + "'!");
        }
        return Collections.unmodifiableMap(properties);
    }

    private static <T extends AnnotatedElement> void sortByAnnotation(T[] methods) {
        Arrays.sort(methods, (o1, o2) -> {
            Order order1 = o1.getAnnotation(Order.class);
            Order order2 = o2.getAnnotation(Order.class);
            if (order1 == null && order2 == null) return 0; // Both methods are unannotated
            if (order1 == null) return -1; // o1 is unannotated, so it comes first
            if (order2 == null) return 1; // o2 is unannotated, so it comes first
            // Both methods have the annotation, compare their values
            return Integer.compare(order1.value(), order2.value());
        });
    }

    private static void parse(@NotNull Method method, @NotNull Map<String, SpecProperty> properties) {
        String key = keyOf(method);
        SpecProperty existing = properties.computeIfAbsent(key, SpecProperty::new);
        @Nullable List<String> comments = commentsOf(method);
        if (Arrays.stream(method.getAnnotations()).anyMatch(SpecProperty::isHandledByProxy)) {
            existing.isHandledByProxy = true;
            existing.getter = method;
            existing.setType(method.getReturnType());
            return;
        }
        if (comments != null) {
            if (existing.comments.isEmpty()) existing.comments = comments;
            else throw new IllegalArgumentException("Inconsistent comments for property '" + key + "'");
        }
        if (method.getReturnType() == Void.TYPE || impliesSetter(method)) {
            if (existing.setter != null)
                throw new IllegalArgumentException("Found 2 setters for property '" + key + "'!");
            if (method.getReturnType() != Void.TYPE)
                throw new IllegalArgumentException("Setter for property '" + key + "' must return void!");
            if (method.getParameterCount() == 0)
                throw new IllegalArgumentException("Setter for property '" + key + "' has no parameters!");
            if (method.getParameterCount() > 1)
                throw new IllegalArgumentException("Setter for property '" + key + "' has more than 1 parameter!");
            existing.setType(method.getParameterTypes()[0]);
            existing.setter = method;
        } else {
            if (existing.getter != null)
                throw new IllegalArgumentException("Found 2 getters for property '" + key + "'!");
            if (method.getParameterCount() != 0)
                throw new IllegalArgumentException("Getter for property '" + key + "' cannot take parameters!");
            existing.setType(method.getReturnType());
            existing.getter = method;
        }
    }

    private static boolean isHandledByProxy(Annotation annotation) {
        return annotation.annotationType().isAnnotationPresent(HandledByProxy.class);
    }

    private static @Nullable List<String> commentsOf(@NotNull Method method) {
        Comment comment = method.getAnnotation(Comment.class);
        if (comment != null) {
            String[] value = comment.value();
            return Arrays.stream(value).flatMap(NEW_LINE::splitAsStream).collect(toList());
        }
        return null;
    }

    public static @NotNull List<String> headerOf(@NotNull Class<?> type) {
        ConfigSpec spec = type.getAnnotation(ConfigSpec.class);
        if (spec != null) {
            String[] value = spec.header();
            return Arrays.stream(value).flatMap(NEW_LINE::splitAsStream).collect(toList());
        }
        return Collections.emptyList();
    }

    @Override
    public String toString() {
        return "SpecProperty(key='" + key + "')";
    }

    public Class<?> type() {
        return type;
    }
}
