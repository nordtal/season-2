package eu.nordtal.jcore.config.spec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Written for nordtal.eu - <b>not</b> part of the vendored Spec library, unlike most of this package.
 *
 * Declares the set of values the interface should offer for this setting, and whether the field
 * next to them is closed or has a free-text box beside it. This is only needed for a scalar whose
 * Java type does not already say so - a real Java {@code enum} property carries its allowed
 * values on the type itself and never needs this annotation (its choices are always
 * {@link #strict()}, since a free-text value could never deserialize into it anyway).
 *
 * Whether a setting is offered as a closed list or as suggestions beside free text is a decision
 * made <b>per setting</b>, not once for the whole config system - that is the point of this being
 * an annotation parameter rather than a global switch.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowedValues {

    /**
     * The values to offer.
     *
     * @return the allowed (or suggested) values, in display order
     */
    String[] value();

    /**
     * Whether the list is closed.
     *
     * @return {@code true} for a select with no free text ("strict"); {@code false} for a select
     * with a free-text field beside it ("suggestion")
     */
    boolean strict() default true;
}
