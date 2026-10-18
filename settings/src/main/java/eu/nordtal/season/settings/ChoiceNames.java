package eu.nordtal.season.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the values of a closed choice and sets what Steward draws before each name.
 *
 * The name of a value is the plugin text {@code <value()>.<value in kebab case>}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ChoiceNames {

    String value();

    /** The item drawn for each value, in the order the choice lists them. */
    String[] icons() default {};
}
