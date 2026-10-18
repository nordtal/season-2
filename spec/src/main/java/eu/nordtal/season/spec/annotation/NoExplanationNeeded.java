package eu.nordtal.season.spec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a setting that needs no explanation. The interface then shows none, not an empty one.
 *
 * The schema refuses it beside {@link Explain @Explain}. Written for nordtal.eu, not part of Spec.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NoExplanationNeeded {}
