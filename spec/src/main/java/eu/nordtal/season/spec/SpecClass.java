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
 *   - the comments written beside each key removed
 */
package eu.nordtal.season.spec;

import static eu.nordtal.season.spec.SpecProperty.headerOf;
import static eu.nordtal.season.spec.SpecProperty.propertiesOf;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The declared shape of one {@code @ConfigSpec} interface: its properties and headers. */
public final class SpecClass {

    private final Class<?> type;
    private final Map<String, SpecProperty> properties;
    private final List<String> headers;

    SpecClass(final Class<?> type, final Map<String, SpecProperty> properties, final List<String> headers) {
        this.type = type;
        this.properties = properties;
        this.headers = headers;
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
