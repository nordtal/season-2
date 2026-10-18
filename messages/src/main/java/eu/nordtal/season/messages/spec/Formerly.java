package eu.nordtal.season.messages.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the former names of a renamed or moved key, so an admin's override follows the key.
 * Each name is {@code key} in this bundle or {@code bundle/key} in another. The build writes them
 * into the schema of the bundle.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Formerly {
    String[] value();
}
