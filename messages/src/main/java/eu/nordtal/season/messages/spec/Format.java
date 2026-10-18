package eu.nordtal.season.messages.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sets the format of the texts below it, which otherwise is {@link MessageSpec#format()}.
 * It sits on a message, a section's accessor or a section's interface, and the nearest one wins.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Format {
    TextFormat value();
}
