package eu.nordtal.season.messages.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Every key a message value may take: {@code key} in this bundle, {@code bundle/key} or {@code bundle/*} elsewhere.
 * A layout shows them wherever it places the message; the first is previewed without an @Example, which one taking
 * a whole bundle must have. A key nested in several layouts is a building block of them.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER})
public @interface Takes {
    String[] value();
}
