package eu.nordtal.season.messages.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the interface that describes one message bundle, {@code messages/<bundle>/}.
 * A method returning {@link eu.nordtal.season.messages.MessageRef} is a key, one returning an interface a section,
 * and a context parameter a role. Where its texts are shown is {@link Shown}'s.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MessageSpec {
    /** The bundle directory under {@code messages/}, e.g. {@code smp}. */
    String value();

    /** How the bundle's texts are written, unless {@link Format} says otherwise below. */
    TextFormat format() default TextFormat.MINIMESSAGE;
}
