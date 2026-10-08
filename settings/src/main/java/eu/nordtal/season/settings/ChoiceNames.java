package eu.nordtal.season.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Where a closed choice's values are named and what Steward draws before each name.
 *
 * A value's name is the plugin's text {@code <value()>.<value in kebab case>}, edited on the Texts page like any other.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ChoiceNames {

    /** The bundle key the values' names sit under. */
    String value();

    /** The item drawn for each value of the choice, in the order the choice lists them; none draws the name alone. */
    String[] icons() default {};
}
