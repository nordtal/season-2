package eu.nordtal.season.messages.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Every key a value that is a message may take, each {@code key} in this bundle or {@code bundle/key} in another.
 * A layout shows them wherever it places the message, which makes a key nested in several layouts a building block;
 * the first one is previewed when the value has no {@link eu.nordtal.season.messages.value.Example}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER})
public @interface Takes {
    String[] value();
}
