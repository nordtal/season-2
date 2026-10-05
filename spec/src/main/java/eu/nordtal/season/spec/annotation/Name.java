package eu.nordtal.season.spec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The name a person reads for a setting or a section in place of its key, which it is otherwise derived from.
 *
 * On a getter returning a nested spec, or on a {@code @ConfigSpec} interface, it names the section. Not part of Spec.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Name {

    /**
     * The name shown in the interface.
     *
     * @return the display name
     */
    String value();
}
