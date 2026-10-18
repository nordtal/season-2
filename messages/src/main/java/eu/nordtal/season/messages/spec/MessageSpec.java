package eu.nordtal.season.messages.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the interface of one message bundle, {@code messages/<bundle>/}.
 * A method that returns a {@link eu.nordtal.season.messages.MessageRef} is a key, one that returns
 * an interface is a section, and a context parameter is a role. {@link Shown} says where it shows.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MessageSpec {
    String value();

    /** The format of the texts, unless a {@link Format} below says otherwise. */
    TextFormat format() default TextFormat.MINIMESSAGE;
}
