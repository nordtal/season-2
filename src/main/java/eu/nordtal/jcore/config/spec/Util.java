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

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.*;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A utility class with small helper functions
 */
final class Util {

    private Util() {
    }

    /**
     * Returns the {@link Class} object representing the class or interface
     * that declared this type.
     *
     * @return the {@link Class} object representing the class or interface
     * that declared this type
     */
    public static Class<?> getRawType(Type type) {
        if (type instanceof Class<?>) {
            // type is a normal class.
            return (Class<?>) type;

        } else if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;

            // I'm not exactly sure why getRawType() returns Type instead of Class.
            // Neal isn't either but suspects some pathological case related
            // to nested classes exists.
            Type rawType = parameterizedType.getRawType();
            if (!(rawType instanceof Class)) {
                throw new IllegalStateException("Expected a Class, found a " + rawType);
            }
            return (Class<?>) rawType;

        } else if (type instanceof GenericArrayType) {
            Type componentType = ((GenericArrayType) type).getGenericComponentType();
            return Array.newInstance(getRawType(componentType), 0).getClass();

        } else if (type instanceof TypeVariable) {
            // we could use the variable's bounds, but that won't work if there are multiple.
            // having a raw type that's more general than necessary is okay
            return Object.class;

        } else if (type instanceof WildcardType) {
            return getRawType(((WildcardType) type).getUpperBounds()[0]);

        } else {
            String className = type == null ? "null" : type.getClass().getName();
            throw new IllegalArgumentException("Expected a Class, ParameterizedType, or "
                    + "GenericArrayType, but <" + type + "> is of type " + className);
        }
    }

    /**
     * Returns the first generic type of the given class. Because
     * classes do not have generics, this function emits a warning
     * to inform them that they probably passed the wrong {@code type}
     * argument, and meant to invoke {@link #getFirstGeneric(Type, Type)} instead.
     *
     * @param cl       The class. This parameter is ignored
     * @param fallback The fallback to return
     * @return The fallback type
     * @see #getFirstGeneric(Type, Type)
     * @deprecated Classes do not have generics. You might have passed
     * the wrong parameters.
     */
    @Deprecated
    @Contract("_,_ -> param2")
    public static Type getFirstGeneric(@NotNull Class<?> cl, @NotNull Type fallback) {
        return fallback;
    }

    /**
     * Returns the first generic type of the given (possibly parameterized)
     * type {@code genericType}. If the type is not parameterized,
     * this will return {@code fallback}.
     *
     * @param genericType The generic type
     * @param fallback    The fallback to return
     * @return The generic type
     */
    public static Type getFirstGeneric(@NotNull Type genericType, @NotNull Type fallback) {
        try {
            return ((ParameterizedType) genericType).getActualTypeArguments()[0];
        } catch (ClassCastException e) {
            return fallback;
        }
    }

    /**
     * Legally stolen and re-adapted from Guava's PeekingImpl class
     * <p>
     * A {@link Iterator} wrapper that allows peeking at the next element
     * without advancing the iterator.
     *
     * @param <E> The element type
     */
    public static final class PeekingIterator<E> implements Iterator<E> {

        private final @NotNull Iterator<? extends E> iterator;
        private @Nullable E peekedElement;
        private boolean hasPeeked;

        PeekingIterator(@NotNull Iterator<? extends E> iterator) {
            this.iterator = iterator;
        }

        /**
         * Returns {@code true} if there are more elements in the iteration.
         *
         * @return {@code true} if the iteration has more elements.
         */
        public boolean hasNext() {
            return this.hasPeeked || this.iterator.hasNext();
        }

        /**
         * Returns the next element in the iteration. If peeked, returns the peeked element.
         *
         * @return The next element.
         * @throws NoSuchElementException If no more elements.
         */
        public E next() {
            if (!this.hasPeeked) {
                return this.iterator.next();
            } else {
                E result = this.peekedElement;
                this.hasPeeked = false;
                this.peekedElement = null;
                return result;
            }
        }

        /**
         * Removes the last element returned by {@code next()}.
         *
         * @throws IllegalStateException If {@code peek()} was called after the last {@code next()}.
         */
        public void remove() {
            if (hasPeeked)
                throw new IllegalStateException("Can't remove after you've peeked at next");
            this.iterator.remove();
        }

        /**
         * Peeks at the next element without advancing the iterator.
         *
         * @return The next element.
         * @throws NoSuchElementException If no more elements.
         */
        public E peek() {
            if (!this.hasPeeked) {
                this.peekedElement = this.iterator.next();
                this.hasPeeked = true;
            }

            return this.peekedElement;
        }

        /**
         * Creates a new {@code PeekingIterator} from the given iterator.
         *
         * @param <E>      The type of elements.
         * @param iterator The iterator to wrap.
         * @return A new {@code PeekingIterator}.
         */
        public static <E> @NotNull PeekingIterator<E> from(@NotNull Iterator<E> iterator) {
            return new PeekingIterator<>(iterator);
        }
    }
}
