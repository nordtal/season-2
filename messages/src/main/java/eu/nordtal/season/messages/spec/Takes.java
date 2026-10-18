package eu.nordtal.season.messages.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Lists the keys a message value may take.
 * A key is {@code key} in this bundle, {@code bundle/key} or {@code bundle/*} in another.
 * A value that takes a whole bundle needs an {@code @Example}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface Takes {
    String[] value();
}
