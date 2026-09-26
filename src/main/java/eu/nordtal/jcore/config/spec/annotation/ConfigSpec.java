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
 *   - header() javadoc rewritten: it goes into the schema, not the YAML (steward/67, 2026-09-16)
 */
package eu.nordtal.jcore.config.spec.annotation;

import java.lang.annotation.*;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a config specification interface
 */
@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigSpec {

    /**
     * The prose describing the file as a whole. Each value is a separate line; an entry that
     * itself contains a newline counts as several.
     * <p>
     * <b>This is no longer a YAML comment block.</b> Upstream Spec wrote it at the top of the
     * file, each line prefixed with {@code '# '} - and entries starting with {@code '#'} without
     * the space, so a row of hashes could serve as a visual separator. jcore 4.0.0 stopped writing
     * comments into the YAML entirely, which left this annotation describing a rendering that no
     * longer happened and writing to no file at all.
     * <p>
     * Since 4.1.0 (steward/67, 2026-09-16) it is carried into
     * {@code <basename>.schema.json} instead, as the {@code explanation} of the root
     * {@link eu.nordtal.jcore.config.schema.SchemaNode} - the node that stands for the whole file,
     * the same way this text does. The lines are joined with {@code '\n'} and are otherwise taken
     * verbatim: no {@code '# '} is added, and a leading {@code '#'} is neither added nor stripped,
     * because JSON is not YAML and nothing downstream is going to read it as a comment. A row of
     * hashes written for the old separator effect is therefore now a row of hashes in the text.
     * <p>
     * Write it for the person who has to operate the file, because that is who reads it: this is
     * the only place a file-wide instruction can be said - "supply these through the environment",
     * "this file is rewritten on every start" - since a per-setting
     * {@link eu.nordtal.jcore.config.spec.annotation.Explain @Explain} is attached to one key.
     *
     * @return The header lines, or an empty array for a file that needs none
     */
    @NotNull
    String[] header() default {};
}
