package eu.nordtal.season.settings.rule;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Refuses a list whose entries, or the same field of its entries, fall from one entry to the next. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Ascending {

    /** The key of the numeric field compared in each entry, or empty for the entries themselves. */
    String value() default "";
}
