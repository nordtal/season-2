package eu.nordtal.season.messages.value;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The example value an editor shows before a live value is known and checks the length limit with.
 * It is written as {@link Kind#example} reads it, and a choice's example is one of its values.
 * Without an example, an enum uses its first constant and any other value uses the kind's default.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface Example {
    String value();
}
