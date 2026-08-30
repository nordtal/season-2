package eu.nordtal.jcore.config;

/**
 * Checks that a freshly loaded configuration makes sense.
 * <p>
 * Deliberately a plain functional interface rather than Jakarta Bean Validation: the checks this
 * organisation actually needs are a handful of if-statements, and Hibernate Validator would add
 * around 1.4 MiB and an EL dependency to every plugin jar for them. jcore's own
 * {@code DatabaseConfig} already validates this way.
 * <p>
 * Throw {@link IllegalArgumentException} with a message an operator can act on; the loader wraps
 * it in a {@link eu.nordtal.jcore.config.exception.ConfigValidationException} naming the file.
 *
 * @param <T> the spec interface type
 */
@FunctionalInterface
public interface ConfigValidator<T> {

    /**
     * @param config the loaded configuration, with the environment overlay already applied
     * @throws IllegalArgumentException if a value is not usable
     */
    void validate(T config);
}
