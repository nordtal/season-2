package eu.nordtal.s2.common.message.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The name an admin reads for this message or section in the Steward UI.
 *
 * Plain language, standing on its own in a search result.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Name {
    String value();
}
