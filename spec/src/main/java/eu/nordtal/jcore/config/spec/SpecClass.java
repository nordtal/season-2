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
 */
package eu.nordtal.jcore.config.spec;

import static eu.nordtal.jcore.config.spec.SpecProperty.headerOf;
import static eu.nordtal.jcore.config.spec.SpecProperty.propertiesOf;

import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;

/** The declared shape of one {@code @ConfigSpec} interface: its properties, headers and comments. */
public final class SpecClass {

    /** The synthetic key segment used for an element of an array/list in a dotted path. */
    public static final String ARRAY_INDEX = "<arr>";

    private final Class<?> type;
    private final Map<String, SpecProperty> properties;
    private @Nullable Map<String, String> comments;
    private final List<String> headers;

    SpecClass(final Class<?> type, final Map<String, SpecProperty> properties, final List<String> headers) {
        this.type = type;
        this.properties = properties;
        this.headers = headers;
    }

    private Map<String, String> computeComments() {
        final Map<String, String> comments = new HashMap<>();
        computeCommentsRecursively(comments, properties.values(), "", 0);
        return comments;
    }

    private static void computeCommentsRecursively(
            final Map<String, String> comments,
            final Collection<SpecProperty> properties,
            final String parentPath,
            final int indent) {
        for (final SpecProperty property : properties) {
            final boolean isSpec = Specs.isConfigSpec(property.type());
            if (!property.hasComments() && !isSpec) continue;
            final String indentStr = spaces(indent);
            final String commentPath = parentPath.isEmpty() ? property.key() : parentPath + '.' + property.key();
            final StringJoiner commentsString = new StringJoiner(System.lineSeparator(), "\n", "");
            for (final String comment : property.comments()) {
                commentsString.add(indentStr + "# " + comment);
            }
            comments.put(commentPath, commentsString.toString());
            if (isSpec) {
                final SpecClass bpc = Specs.from(property.type());
                computeCommentsRecursively(comments, bpc.properties().values(), commentPath, indent + 2);
            } else if (isCollection(property.type())) {
                final Class<?> type = getCollectionType(property.getter().getGenericReturnType());
                if (Specs.isConfigSpec(type)) {
                    final SpecClass bpc = Specs.from(type);
                    computeCommentsRecursively(
                            comments, bpc.properties().values(), commentPath + "." + ARRAY_INDEX, indent + 2);
                }
            }
        }
    }

    private static Class<?> getCollectionType(final java.lang.reflect.Type returnType) {
        final Class<?> rawType = Util.getRawType(returnType);
        if (Collection.class.isAssignableFrom(rawType)) {
            return Util.getRawType(Util.getFirstGeneric(returnType, Object.class));
        } else {
            return rawType.getComponentType();
        }
    }

    private static boolean isCollection(final Class<?> aClass) {
        return Collection.class.isAssignableFrom(aClass) || aClass.isArray();
    }

    private static String spaces(final int times) {
        final char[] c = new char[times];
        Arrays.fill(c, ' ');
        return new String(c);
    }

    static SpecClass from(final Class<?> type) {
        Objects.requireNonNull(type, "interface cannot be null!");
        if (!type.isInterface()) throw new IllegalArgumentException("Class is not an interface: " + type.getName());
        if (!type.isAnnotationPresent(ConfigSpec.class))
            throw new IllegalArgumentException("Interface does not have @ConfigSpec on it!");
        final List<String> headers = headerOf(type);
        final Map<String, SpecProperty> properties = propertiesOf(type);
        return new SpecClass(type, properties, headers);
    }

    /**
     * The comments to write beside each key, keyed by dotted path.
     *
     * @return the comments, keyed by dotted path
     */
    public Map<String, String> comments() {
        if (comments == null) comments = computeComments();
        return comments;
    }

    public List<String> headers() {
        return headers;
    }

    /**
     * The spec interface this describes.
     *
     * @return the spec interface
     */
    public Class<?> type() {
        return type;
    }

    public Map<String, SpecProperty> properties() {
        return properties;
    }

    /**
     * Creates a default-valued instance of this spec, typed by the given class token rather than an unchecked cast.
     *
     * @param <T> the spec interface type
     * @param type the spec interface, which must be the one this {@code SpecClass} describes
     * @return a new instance with every property at its default value
     */
    public <T> T createDefault(final Class<T> type) {
        return type.cast(Specs.createDefault(type));
    }

    /**
     * Creates an instance of this spec backed by {@code map}, typed by the given class token.
     *
     * @param <T> the spec interface type
     * @param type the spec interface, which must be the one this {@code SpecClass} describes
     * @param map the values to back the instance with
     * @return a new instance backed by {@code map}
     */
    public <T> T createUnsafe(final Class<T> type, final Map<String, Object> map) {
        return type.cast(Specs.createUnsafe(type, map));
    }
}
