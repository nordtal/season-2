package eu.nordtal.season.spec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The short sentence a person reads beside a setting in Steward; {@link Comment @Comment} is the long form.
 *
 * nordtal.eu's own, not part of Spec.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Explain {

    /**
     * The short sentence shown in the interface next to this setting.
     *
     * @return the explanation text
     */
    String value();
}
