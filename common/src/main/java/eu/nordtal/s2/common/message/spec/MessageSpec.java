package eu.nordtal.s2.common.message.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the interface that describes one message bundle, {@code messages/<bundle>/}.
 *
 * A method returning {@link eu.nordtal.s2.common.message.MessageRef} is one key; a method
 * returning another interface is a section, and its segment prefixes every key inside it. A
 * segment is the method name in kebab case unless {@link Key} says otherwise, and every parameter
 * carries {@link Arg}. A parameter whose type is a {@link eu.nordtal.s2.common.message.context.MessageContext}
 * is a role: its {@code Arg} names the role, and the text reads the context's properties as
 * <code>{role.property}</code>. {@link MessageSpecCheck} holds a spec and its {@code en}/{@code de} files to
 * each other, key for key and placeholder for placeholder.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MessageSpec {
    /** The bundle directory under {@code messages/}, e.g. {@code smp}. */
    String value();

    /** How the bundle's texts are written, unless {@link Format} says otherwise below. */
    TextFormat format() default TextFormat.MINIMESSAGE;

    /** Where the bundle's texts are shown, unless {@link Shown} says otherwise below. */
    Display shown() default Display.CHAT;
}
