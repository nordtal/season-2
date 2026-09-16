package eu.nordtal.jcore.config.spec.annotation;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Written for nordtal.eu - <b>not</b> part of the vendored Spec library, unlike most of this
 * package.
 * <p>
 * Marks one entry of a list-of-settings property as one the interface must never let an operator
 * remove, identified by the value one of its own fields carries -
 * {@code @Protected(field = "tag", value = "en")} on a {@code List<Language>} property says the
 * entry whose {@code tag} equals {@code "en"} stays, whatever else in the list changes.
 * <p>
 * Only meaningful on a property whose element type is itself a {@code @ConfigSpec} - a list of
 * plain scalars has no field of its own to match against, and the schema writer refuses to build a
 * schema that puts this annotation anywhere else (a scalar list, a single value, or a nested map).
 * {@link #field()} must name a real property of the element type; a typo there would silently
 * protect nothing, so the writer refuses that too rather than building a schema nobody could act on.
 * <p>
 * <b>This is a description, not an enforcement.</b> The annotation only ever lands on the
 * {@code SchemaNode} the schema writer builds
 * ({@link eu.nordtal.jcore.config.schema.SchemaNode#protectedEntry()}); jcore itself never rejects a
 * removal on the strength of it. A consumer that edits the file - steward-worker's
 * {@code ConfigFiles.removeSection} is the one this was built for - is what turns the description
 * into a refusal.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Protected {

    /**
     * The element's own field to match against.
     *
     * @return the property key on the list's element type
     */
    @NotNull String field();

    /**
     * The value {@link #field()} must equal for that entry to be protected.
     *
     * @return the protected value
     */
    @NotNull String value();
}
