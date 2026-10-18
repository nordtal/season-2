package eu.nordtal.season.spec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a setting as a credential. The interface shows a password field, and no log prints the value.
 *
 * Written for nordtal.eu, not part of Spec.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Secret {}
