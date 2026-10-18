package eu.nordtal.season.spec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the entry of a list of nested specs that a consumer never removes. Spec does not define it.
 *
 * {@code @Protected(field = "tag", value = "en")} protects the entry whose {@code tag} is {@code "en"}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Protected {

    /**
     * Returns the field of the entry to compare.
     *
     * @return the property key on the element type of the list
     */
    String field();

    /**
     * Returns the value that {@link #field()} must equal for the entry to be protected.
     *
     * @return the protected value
     */
    String value();
}
