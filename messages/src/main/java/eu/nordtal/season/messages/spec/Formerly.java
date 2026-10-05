package eu.nordtal.season.messages.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The names this message had before, so an admin's override follows a renamed or moved key instead of being orphaned.
 *
 * Each is {@code key} in this bundle or {@code bundle/key} in another, written into the bundle's schema at build time.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Formerly {
    String[] value();
}
