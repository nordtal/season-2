package eu.nordtal.jcore.config.spec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jetbrains.annotations.NotNull;

/**
 * Written for nordtal.eu - <b>not</b> part of the vendored Spec library, unlike most of this
 * package.
 * <p>
 * The name a person reads for a setting or a section in an interface, in place of its key. The
 * key is what the YAML says; {@code bunq-api-key} is not what an admin calls the thing.
 * <p>
 * On a getter it names that setting, or that section when the getter returns a nested
 * {@code @ConfigSpec}. On a {@code @ConfigSpec} interface it names the section wherever the
 * interface is used, unless the getter carries its own. Without either, the schema falls back to a
 * name derived from the key.
 * <p>
 * Example:
 * <pre>{@code @ConfigSpec
 * @Name("Payments")
 * public interface PaymentProcessingSpec {
 *
 *     @Name("Check interval")
 *     @Explain("How often payments are checked, in seconds.")
 *     default long checkIntervalSeconds() { return 10; }
 * }}</pre>
 *
 * @see Explain
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Name {

    /**
     * The name shown in the interface.
     *
     * @return the display name
     */
    @NotNull
    String value();
}
