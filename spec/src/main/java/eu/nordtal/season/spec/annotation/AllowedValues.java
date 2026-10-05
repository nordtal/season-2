package eu.nordtal.season.spec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the values the interface offers for a setting, and whether they are the only ones or suggestions.
 *
 * A Java {@code enum} property needs none: its constants are the choices, always strict. Not part of Spec.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowedValues {

    /**
     * The values to offer.
     *
     * @return the allowed (or suggested) values, in display order
     */
    String[] value();

    /**
     * Whether the list is closed.
     *
     * @return {@code true} for a select with no free text ("strict"); {@code false} for a select
     * with a free-text field beside it ("suggestion")
     */
    boolean strict() default true;
}
