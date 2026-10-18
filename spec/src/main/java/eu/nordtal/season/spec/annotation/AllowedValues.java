package eu.nordtal.season.spec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lists the values that the interface offers for a setting. Spec does not define it.
 *
 * An {@code enum} property needs no list, because its constants are the choices and are always strict.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowedValues {

    String[] value();

    /**
     * Tells whether the list is closed.
     *
     * @return {@code true} for a select without free text (strict), {@code false} for a select with a free-text field
     * (suggestion)
     */
    boolean strict() default true;
}
