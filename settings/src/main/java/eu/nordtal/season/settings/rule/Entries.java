package eu.nordtal.season.settings.rule;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Refuses a list with another number of entries than {@code exactly}, or with more than {@code most}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Entries {

    /** The one count the list takes, or {@code -1} for any. */
    int exactly() default -1;

    /** The most entries the list takes, or {@code -1} for no limit. */
    int most() default -1;
}
