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
 *   - reload() no longer calls config.setComments()/setHeaders(): the YAML this writes is
 *     comment-free, in step with ConfigHandle. @Comment still exists and SpecClass
 *     still computes it; nothing here feeds it to a file any more.
 */
package eu.nordtal.jcore.config.spec;

import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Reload;
import eu.nordtal.jcore.config.spec.annotation.Save;
import java.lang.reflect.Proxy;
import java.util.Objects;

/**
 * Creates a {@link Proxy} for {@link ConfigSpec} classes and handles its {@link Save} and {@link Reload} methods.
 *
 * Using this allows the user to store instances of the {@link ConfigSpec} interfaces
 * while at the same time making sure they always have the latest value
 * if it gets reloaded.
 *
 * It also allows specs to include methods like {@link Reload} and {@link Save},
 * which we intercept in the proxy.
 *
 * @param <T> The property type
 */
public final class SpecReference<T> {

    /**
     * The spec type
     */
    private final Class<T> type;

    /**
     * The configuration file containing the data
     */
    private final CommentedConfiguration config;

    /**
     * The underlying value. This can get changed at any time
     */
    private T value;

    /**
     * The proxy (reference) that redirects calls to this object or the underlying value
     */
    private final T proxy;

    /**
     * @param type   the spec interface
     * @param config the configuration file backing it
     */
    public SpecReference(final Class<T> type, final CommentedConfiguration config) {
        this.type = type;
        this.config = config;
        this.proxy = SpecProxy.proxy(type, this::value, this::reload, this::save);
        reload();
    }

    /**
     * Returns the type of the interface this reference is pointing to
     *
     * @return the interface type
     */
    public Class<?> type() {
        return type;
    }

    /**
     * The wrapped value. It cannot be reloaded or saved itself; those are handled by {@link #proxy}.
     *
     * @return The underlying value
     */
    private T value() {
        return value;
    }

    /**
     * Returns the top-level proxy. This proxy allows reloading and saving.
     *
     * @return The top-level wrapper proxy.
     */
    public T get() {
        return proxy;
    }

    /**
     * Reloads the content of the object.
     */
    public void reload() {
        config.load();
        this.value = Objects.requireNonNull(type.cast(config.getAs(type)), "a loaded config is never JSON null");
    }

    /**
     * Saves the current object to the config
     */
    public void save() {
        config.setTo(this.value, this.type);
        config.save();
    }

    /**
     * Sets the value this reference is pointing to, to the given value.
     *
     * @param value The new value
     */
    public void set(final T value) {
        Objects.requireNonNull(value, "value cannot be null!");
        this.value = value;
    }
}
