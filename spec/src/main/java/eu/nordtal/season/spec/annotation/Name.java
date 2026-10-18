package eu.nordtal.season.spec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sets the name that the interface shows for a setting or a section. Spec does not define it.
 *
 * On a getter that returns a nested spec, or on a {@code @ConfigSpec} interface, it names the section.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Name {

    String value();
}
