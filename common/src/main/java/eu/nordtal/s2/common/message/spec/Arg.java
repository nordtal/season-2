package eu.nordtal.s2.common.message.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The placeholder a parameter fills, written <code>{name}</code> in the text, or <code>&lt;name&gt;</code> for an Adventure Component. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER})
public @interface Arg {
    String value();
}
