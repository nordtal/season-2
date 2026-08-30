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
 */
package eu.nordtal.jcore.config.spec;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;

import java.util.*;

import static eu.nordtal.jcore.config.spec.SpecProperty.headerOf;
import static eu.nordtal.jcore.config.spec.SpecProperty.propertiesOf;

public final class SpecClass {

    public static final String ARRAY_INDEX = "<arr>";

    private final Class<?> type;
    private final @Unmodifiable Map<String, SpecProperty> properties;
    private @Nullable Map<String, String> comments;
    private final @NotNull List<String> headers;

    SpecClass(
            @NotNull Class<?> type,
            @NotNull Map<String, SpecProperty> properties,
            @NotNull List<String> headers
    ) {
        this.type = type;
        this.properties = properties;
        this.headers = headers;
    }

    private @NotNull Map<String, String> computeComments() {
        Map<String, String> comments = new HashMap<>();
        computeCommentsRecursively(comments, properties.values(), "", 0);
        return comments;
    }

    private static void computeCommentsRecursively(
            @NotNull Map<String, String> comments,
            @NotNull Collection<SpecProperty> properties,
            @NotNull String parentPath,
            int indent
    ) {
        for (SpecProperty property : properties) {
            boolean isSpec = Specs.isConfigSpec(property.type());
            if (!property.hasComments() && !isSpec)
                continue;
            String indentStr = spaces(indent);
            String commentPath = parentPath.isEmpty() ? property.key() : parentPath + '.' + property.key();
            StringJoiner commentsString = new StringJoiner(System.lineSeparator(), "\n", "");
            for (String comment : property.comments()) {
                commentsString.add(indentStr + "# " + comment);
            }
            comments.put(commentPath, commentsString.toString());
            if (isSpec) {
                SpecClass bpc = Specs.from(property.type());
                computeCommentsRecursively(
                        comments,
                        bpc.properties().values(),
                        commentPath,
                        indent + 2
                );
            } else if (isCollection(property.type())) {
                Class<?> type = getCollectionType(property.getter().getGenericReturnType());
                if (Specs.isConfigSpec(type)) {
                    SpecClass bpc = Specs.from(type);
                    computeCommentsRecursively(
                            comments,
                            bpc.properties().values(),
                            commentPath + "." + ARRAY_INDEX,
                            indent + 2
                    );
                }
            }
        }
    }

    private static Class<?> getCollectionType(java.lang.reflect.Type returnType) {
        Class<?> rawType = Util.getRawType(returnType);
        if (Collection.class.isAssignableFrom(rawType)) {
            return Util.getRawType(Util.getFirstGeneric(returnType, Object.class));
        } else {
            return rawType.getComponentType();
        }
    }

    private static boolean isCollection(Class<?> aClass) {
        return Collection.class.isAssignableFrom(aClass) || aClass.isArray();
    }

    private static String spaces(int times) {
        char[] c = new char[times];
        Arrays.fill(c, ' ');
        return new String(c);
    }

    static @NotNull SpecClass from(@NotNull Class<?> type) {
        Objects.requireNonNull(type, "interface cannot be null!");
        if (!type.isInterface())
            throw new IllegalArgumentException("Class is not an interface: " + type.getName());
        if (!type.isAnnotationPresent(ConfigSpec.class))
            throw new IllegalArgumentException("Interface does not have @ConfigSpec on it!");
        List<String> headers = headerOf(type);
        Map<String, SpecProperty> properties = propertiesOf(type);
        return new SpecClass(type, properties, headers);
    }

    public @NotNull Map<String, String> comments() {
        if (comments == null)
            comments = computeComments();
        return comments;
    }

    public @NotNull List<String> headers() {
        return headers;
    }

    public @NotNull @Unmodifiable Map<String, SpecProperty> properties() {
        return properties;
    }

    @SuppressWarnings("unchecked")
    public @NotNull <T> T createDefault() {
        return (T) Specs.createDefault(type);
    }

    @SuppressWarnings("unchecked")
    public @NotNull <T> T createUnsafe(Map<String, Object> map) {
        return (T) Specs.createUnsafe(type, map);
    }

}
