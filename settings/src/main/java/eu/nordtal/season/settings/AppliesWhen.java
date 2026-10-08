package eu.nordtal.season.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * That a setting applies only while a sibling holds one of some values, so Steward's form shows it only then.
 *
 * The process still reads the setting either way; whether a value left behind is an error is its own to judge.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AppliesWhen {

    /** The key of the sibling whose value decides. */
    String key();

    /** The sibling's values the setting applies to. */
    String[] values();
}
