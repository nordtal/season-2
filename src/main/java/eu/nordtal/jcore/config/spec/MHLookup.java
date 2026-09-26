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
 *   - de-Lombok: @SneakyThrows replaced with an explicit try/catch
 */
package eu.nordtal.jcore.config.spec;

import static java.lang.invoke.MethodHandles.lookup;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.jspecify.annotations.Nullable;

/**
 * Generates private {@link Lookup}s through reflection, for JVMs where the direct API is unavailable.
 */
final class MHLookup {

    private static @Nullable Constructor<Lookup> constructor;
    private static @Nullable Method privateLookupIn;

    static {
        try {
            privateLookupIn =
                    MethodHandles.class.getDeclaredMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
        } catch (NoSuchMethodException e) {
            try {
                constructor = Lookup.class.getDeclaredConstructor(Class.class);
                constructor.setAccessible(true);
            } catch (NoSuchMethodException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    private MHLookup() {}

    /**
     * Generates a {@link Lookup} that can access private members in the given class.
     *
     * @param cl The class to access
     * @return The created {@link Lookup}
     */
    public static Lookup privateLookupIn(final Class<?> cl) {
        try {
            if (privateLookupIn != null) {
                return (Lookup) privateLookupIn.invoke(null, cl, lookup());
            }
            if (constructor != null) {
                return constructor.newInstance(cl);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to create a private lookup into " + cl.getName(), e);
        }
        throw new IllegalStateException("Failed to create a private lookup into " + cl.getName());
    }
}
