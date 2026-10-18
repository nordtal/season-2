package eu.nordtal.season.spec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sets the sentence that Steward shows between the name of a setting and its input.
 *
 * Written for nordtal.eu, not part of Spec.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Explain {

    String value();
}
