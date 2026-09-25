package eu.nordtal.s2.common.message.context;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Names a {@link MessageContext} record: its key in {@code schema.json} and the name an admin reads. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ContextType {

    /** The type's key, e.g. {@code player}; what the Steward UI finds example values by. */
    String value();

    /** The name an admin reads, e.g. {@code Player}. */
    String name();
}
