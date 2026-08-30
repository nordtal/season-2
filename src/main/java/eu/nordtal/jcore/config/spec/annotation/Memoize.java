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
package eu.nordtal.jcore.config.spec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An annotation that allows to compute certain values and cache their result.
 * <p>
 * This is very useful for heavy, repetitive computations that depend
 * on the configuration values.
 * <p>
 * Note: {@link Memoize @Memoize} does not (yet) consider arguments
 * when caching values. Therefore, it is best to just use it to compute the
 * parts that depend on the configuration values
 * <p>
 * Reloading, resetting, or calling a setter will re-compute
 * all memoized values.
 * <p>
 * Example:
 * <pre>{@code @ConfigSpec
 * public interface SearchArea {
 *
 *     default double radius() {
 *         return 5;
 *     }
 *
 *     void setRadius(double radius);
 *
 *     @Memoize
 *     default double radiusCubed() {
 *         System.out.println("Computing r^3");
 *         return radius() * radius() * radius();
 *     }
 *
 *     @Memoize
 *     default double radiusSquared() {
 *         System.out.println("Computing r^2");
 *         return radius() * radius();
 *     }
 * }}</pre>
 *
 * <pre>{@code
 * SearchArea area = Specs.createDefault(SearchArea.class);
 * System.out.println(area.radiusCubed());
 * System.out.println(area.radiusCubed());
 * area.setRadius(10);
 * System.out.println(area.radiusCubed());
 * System.out.println(area.radiusCubed());
 * }</pre>
 * Will print:
 * <pre>
 * Computing r^3
 * 125.0
 * 125.0
 * Computing r^3
 * 1000.0
 * 1000.0
 * </pre>
 */
@HandledByProxy
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Memoize {
}
