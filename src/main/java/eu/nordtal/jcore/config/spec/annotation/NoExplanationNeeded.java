package eu.nordtal.jcore.config.spec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Written for nordtal.eu - <b>not</b> part of the vendored Spec library, unlike most of this
 * package.
 * <p>
 * Marks a setting as self-explanatory: the interface shows it without an explanation text at all,
 * rather than an empty one. Mutually exclusive with {@link Explain @Explain} - a property carrying
 * both is a contradiction the schema writer refuses to guess at, and fails loudly instead.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NoExplanationNeeded {
}
