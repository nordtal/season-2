package eu.nordtal.season.messages.spec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Every place the texts below it are shown, the one an editor previews first leading.
 * On the spec, a section's accessor, a section's interface or a message; the nearest one wins, and a key that
 * none reaches is refused by {@link MessageSpecCheck}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Shown {
    Display[] value();
}
