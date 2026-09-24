package eu.nordtal.s2.common.message.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the interface that describes one message bundle, {@code messages/<bundle>/}.
 *
 * <p>A method returning {@link eu.nordtal.s2.common.message.MessageRef} is one key; a method
 * returning another interface is a section, and its segment prefixes every key inside it. A
 * segment is the method name in kebab case unless {@link Key} says otherwise, and every parameter
 * carries {@link Arg}. {@link MessageSpecCheck} holds a spec and its {@code en}/{@code de} files to
 * each other, key for key and placeholder for placeholder.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MessageSpec {
    /** The bundle directory under {@code messages/}, e.g. {@code smp}. */
    String value();
}
