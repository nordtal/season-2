package eu.nordtal.season.spec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a setting as self-explanatory: the interface shows no explanation at all, rather than an empty one.
 *
 * The schema refuses it beside {@link Explain @Explain}. nordtal.eu's own, not part of Spec.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NoExplanationNeeded {}
