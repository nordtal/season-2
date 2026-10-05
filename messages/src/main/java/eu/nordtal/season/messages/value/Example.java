package eu.nordtal.season.messages.value;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What an editor shows for a value before a live one is known, and what a length limit is checked with.
 * It is written as {@link Kind#example} reads it; without one the kind's own example stands in.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface Example {
    String value();
}
