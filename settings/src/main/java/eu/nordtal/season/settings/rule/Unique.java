package eu.nordtal.season.settings.rule;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Refuses a list in which two entries, or the same field of two entries, hold one value. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Unique {

    /** The key of the field compared in each entry, or empty for the entries themselves. */
    String value() default "";
}
