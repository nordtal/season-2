package eu.nordtal.season.settings.rule;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Refuses a number below {@code least} or above {@code most}, on a list each entry. A bound left out is open. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Between {

    long least() default Long.MIN_VALUE;

    long most() default Long.MAX_VALUE;
}
