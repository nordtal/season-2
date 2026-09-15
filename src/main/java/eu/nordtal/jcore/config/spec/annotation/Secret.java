package eu.nordtal.jcore.config.spec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Written for nordtal.eu - <b>not</b> part of the vendored Spec library, unlike most of this
 * package.
 * <p>
 * Marks a setting as a credential: a password field in the interface, and never printed to a log.
 * This is a deliberate declaration on top of the key-name heuristic
 * ({@code secret}/{@code token}/{@code password}/{@code key} substrings) that a consumer such as
 * steward-worker's {@code ConfigEntry.isSecretKey} already applies to any file, schema or not -
 * that heuristic stays as the net underneath, it is not replaced by this annotation.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Secret {
}
