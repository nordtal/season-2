package eu.nordtal.jcore.config.spec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Written for nordtal.eu - <b>not</b> part of the vendored Spec library, unlike most of this package.
 *
 * The short sentence a person flipping the switch in the Steward UI reads, as opposed to
 * {@link Comment @Comment}, which is the long form for the person reading the code and is no
 * longer written into the YAML at all (see {@code eu.nordtal.jcore.config.schema.SchemaWriter}).
 * Nothing about {@code @Comment} changes: a property may carry both, one, or neither. This exists
 * because the two audiences turned out to need different lengths of the same idea, and forcing
 * one field to serve both left the short one either too long to fit an interface, or the long one
 * cut down until it stopped explaining anything.
 *
 * Example:
 * {@snippet lang="java" :
 * @ConfigSpec
 * public interface PaymentProcessingSpec {
 *
 *     @Comment({
 *         "How often the bunq account is polled for new payments, in seconds.",
 *         "",
 *         "Lower values notice a payment sooner at the cost of one more API call per interval;",
 *         "bunq's own rate limit is the real ceiling here, not anything jcore enforces."
 *     })
 *     @Explain("How often payments are checked, in seconds.")
 *     default long checkIntervalSeconds() { return 10; }
 * }
 * }
 *
 * @see NoExplanationNeeded
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Explain {

    /**
     * The short sentence shown in the interface next to this setting.
     *
     * @return the explanation text
     */
    String value();
}
