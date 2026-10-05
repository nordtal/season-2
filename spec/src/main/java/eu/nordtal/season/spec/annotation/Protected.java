package eu.nordtal.season.spec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the entry of a list of nested specs a consumer must never remove, by the value of one of its fields.
 *
 * {@code @Protected(field = "tag", value = "en")} keeps the entry whose {@code tag} is {@code "en"}. Not part of Spec.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Protected {

    /**
     * The element's own field to match against.
     *
     * @return the property key on the list's element type
     */
    String field();

    /**
     * The value {@link #field()} must equal for that entry to be protected.
     *
     * @return the protected value
     */
    String value();
}
