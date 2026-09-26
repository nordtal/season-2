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

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Iterator;
import java.util.NoSuchElementException;
import org.jspecify.annotations.Nullable;

/** Small reflection and iterator helpers used by the spec proxy machinery. */
final class Util {

    private Util() {}

    /**
     * Returns the {@link Class} object representing the class or interface that declared this type.
     *
     * @return the {@link Class} object representing the class or interface
     * that declared this type
     */
    public static Class<?> getRawType(final Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;

        } else if (type instanceof ParameterizedType parameterizedType) {
            final Type rawType = parameterizedType.getRawType();
            if (!(rawType instanceof Class)) {
                throw new IllegalStateException("Expected a Class, found a " + rawType);
            }
            return (Class<?>) rawType;

        } else if (type instanceof GenericArrayType genericArrayType) {
            final Type componentType = genericArrayType.getGenericComponentType();
            return Array.newInstance(getRawType(componentType), 0).getClass();

        } else if (type instanceof TypeVariable) {
            // A raw type more general than necessary is harmless; a type variable can have several bounds.
            return Object.class;

        } else if (type instanceof WildcardType wildcardType) {
            return getRawType(wildcardType.getUpperBounds()[0]);

        } else {
            final String className = type == null ? "null" : type.getClass().getName();
            throw new IllegalArgumentException("Expected a Class, ParameterizedType or GenericArrayType, but <" + type
                    + "> is of type " + className);
        }
    }

    /**
     * The first type argument of {@code genericType}, or {@code fallback} if it is not a parameterized type.
     */
    public static Type getFirstGeneric(final Type genericType, final Type fallback) {
        try {
            return ((ParameterizedType) genericType).getActualTypeArguments()[0];
        } catch (ClassCastException e) {
            return fallback;
        }
    }

    /**
     * An iterator wrapper that allows peeking at the next element without advancing it.
     *
     * @param <E> the element type
     */
    public static final class PeekingIterator<E> implements Iterator<E> {

        private final Iterator<? extends E> iterator;
        private @Nullable E peekedElement;
        private boolean hasPeeked;

        PeekingIterator(final Iterator<? extends E> iterator) {
            this.iterator = iterator;
        }

        @Override
        public boolean hasNext() {
            return this.hasPeeked || this.iterator.hasNext();
        }

        /**
         * The next element, or the one {@link #peek()} returned since the last advance.
         */
        @Override
        public E next() {
            if (!this.hasPeeked) {
                return this.iterator.next();
            }
            final E result = this.peekedElement;
            this.hasPeeked = false;
            this.peekedElement = null;
            if (result == null) {
                throw new NoSuchElementException();
            }
            return result;
        }

        /**
         * Removes the last element returned by {@link #next()}.
         *
         * @throws IllegalStateException if {@link #peek()} was called after the last {@link #next()}
         */
        @Override
        public void remove() {
            if (hasPeeked) {
                throw new IllegalStateException("Can't remove after you've peeked at next");
            }
            this.iterator.remove();
        }

        /**
         * Peeks at the next element without advancing the iterator.
         *
         * @return the next element
         * @throws NoSuchElementException if there are no more elements
         */
        public E peek() {
            if (!this.hasPeeked) {
                this.peekedElement = this.iterator.next();
                this.hasPeeked = true;
            }
            if (this.peekedElement == null) {
                throw new NoSuchElementException();
            }
            return this.peekedElement;
        }

        /**
         * Wraps {@code iterator} so its next element can be peeked at without advancing it.
         *
         * @param <E> the element type
         * @param iterator the iterator to wrap
         * @return a peeking view of {@code iterator}
         */
        public static <E> PeekingIterator<E> from(final Iterator<E> iterator) {
            return new PeekingIterator<>(iterator);
        }
    }
}
