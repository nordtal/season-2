package eu.nordtal.season.messages.context;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a {@link MessageContext} record. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ContextType {

    /** The key in {@code schema.json}, such as {@code player}. Steward finds examples by it. */
    String value();

    String name();
}
