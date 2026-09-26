package eu.nordtal.jcore.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.config.internal.EnvOverlay;
import eu.nordtal.jcore.config.spec.SpecAdapterFactory;
import eu.nordtal.jcore.config.spec.Specs;
import java.io.File;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Loads commented YAML configuration files described by an annotated interface.
 *
 * {@snippet lang="java" :
 * @ConfigSpec(header = "Payment processing")
 * public interface PaymentProcessingSpec {
 *
 *     @Order(1) @Key("check-interval-seconds")
 *     @Comment("How often the bunq account is polled, in seconds.")
 *     default long checkIntervalSeconds() { return 10; }
 *
 *     @Reload void reload();
 * }
 *
 * ConfigHandle<PaymentProcessingSpec> handle = ConfigLoader
 *         .builder(Path.of("config/payment-processing.yml"), PaymentProcessingSpec.class)
 *         .validator(config -> {
 *             if (config.checkIntervalSeconds() <= 0)
 *                 throw new IllegalArgumentException("check-interval-seconds must be positive");
 *         })
 *         .load();
 * }
 *
 * The file is created with its defaults and comments if it does not exist and kept in step with
 * the interface on every load. A setting the interface does not declare is refused when it reads
 * as a mistyped declared key, and removed from the file - with a warning and a {@code .bak} - when
 * it does not, which is what a setting the software has dropped looks like. Any single setting can
 * be overridden by an environment variable - see {@link EnvOverlay}.
 *
 * @see ConfigHandle
 */
public final class ConfigLoader {

    /** The organisation-wide environment variable prefix. */
    public static final String DEFAULT_ENV_PREFIX = "NORDTAL";

    /**
     * The Gson every config is serialized through.
     *
     * {@link ToNumberPolicy#LONG_OR_DOUBLE} is not cosmetic housekeeping. Spec round-trips every
     * value through Gson's generic {@code Object} type, and Gson's default policy reads every
     * JSON number back as a {@code Double}, so {@code update-interval: 1} is written back to the
     * file as {@code update-interval: 1.0}. DisplayTags had to know this and set the policy
     * itself; it belongs here so no caller has to.
     */
    private static final Gson DEFAULT_GSON = new GsonBuilder()
            .registerTypeAdapterFactory(SpecAdapterFactory.INSTANCE)
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .create();

    private ConfigLoader() {}

    /**
     * A Gson pre-configured for Spec, to add your own type adapters to.
     *
     * @return a builder that already has the Spec adapter factory and the number policy set
     */
    public static GsonBuilder gsonBuilder() {
        return new GsonBuilder()
                .registerTypeAdapterFactory(SpecAdapterFactory.INSTANCE)
                .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE);
    }

    /**
     * Loads a configuration with the defaults: the {@code NORDTAL} environment prefix, no validation and no load hook.
     *
     * @param <T> the spec interface type
     * @param file the config file to load
     * @param specType the spec interface describing it
     * @return a handle on the loaded config
     * @throws ConfigException if the file cannot be read or written, or contains a mistyped
     *                         setting
     */
    public static <T> ConfigHandle<T> load(final Path file, final Class<T> specType) throws ConfigException {
        return builder(file, specType).load();
    }

    /**
     * Starts building a loader for {@code file}.
     *
     * @param <T> the spec interface type
     * @param file the config file to load
     * @param specType the spec interface describing it
     * @return a builder for further configuration
     */
    public static <T> Builder<T> builder(final Path file, final Class<T> specType) {
        return new Builder<>(file, specType);
    }

    /**
     * Starts building a loader for {@code file}.
     *
     * @param <T> the spec interface type
     * @param file the config file to load
     * @param specType the spec interface describing it
     * @return a builder for further configuration
     */
    public static <T> Builder<T> builder(final File file, final Class<T> specType) {
        return new Builder<>(file.toPath(), specType);
    }

    /** Configures and creates a {@link ConfigHandle}. */
    public static final class Builder<T> {

        private final Path file;
        private final Class<T> specType;
        private Gson gson = DEFAULT_GSON;
        private String envPrefix = DEFAULT_ENV_PREFIX;
        private Function<String, String> environment = System::getenv;
        private ConfigValidator<T> validator = config -> {};
        private Consumer<T> onLoad = config -> {};

        private Builder(final Path file, final Class<T> specType) {
            if (!Specs.isConfigSpec(specType)) {
                throw new IllegalArgumentException(
                        specType.getName() + " must be an interface annotated with @ConfigSpec.");
            }
            // A package-private interface fails far later, deep inside the reflective proxy; name it here instead.
            if (!Modifier.isPublic(specType.getModifiers())) {
                throw new IllegalArgumentException(specType.getName() + " must be public. A config spec is served by a "
                        + "reflective proxy, which cannot read a package-private interface.");
            }
            this.file = file;
            this.specType = specType;
        }

        /**
         * Uses a custom Gson. Build it from {@link ConfigLoader#gsonBuilder()} to keep the Spec adapter in place.
         *
         * @param gson the Gson to use
         * @return this builder
         */
        public Builder<T> gson(final Gson gson) {
            this.gson = gson;
            return this;
        }

        /**
         * Changes the environment variable prefix. Defaults to {@code NORDTAL}.
         *
         * @param envPrefix the prefix to use
         * @return this builder
         */
        public Builder<T> envPrefix(final String envPrefix) {
            this.envPrefix = envPrefix;
            return this;
        }

        /**
         * Replaces the source of environment variables. Intended for tests.
         *
         * @param environment the replacement source
         * @return this builder
         */
        public Builder<T> environment(final Function<String, String> environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Turns the environment overlay off entirely.
         *
         * @return this builder
         */
        public Builder<T> withoutEnvironmentOverlay() {
            this.environment = name -> null;
            return this;
        }

        /**
         * Checks the loaded values, after the environment overlay is applied, so it covers those values too.
         *
         * @param validator the check to run
         * @return this builder
         */
        public Builder<T> validator(final ConfigValidator<T> validator) {
            this.validator = validator;
            return this;
        }

        /**
         * Runs after every successful load and reload, unconditionally, whether or not the file changed.
         *
         * @param onLoad the hook to run
         * @return this builder
         */
        public Builder<T> onLoad(final Consumer<T> onLoad) {
            this.onLoad = onLoad;
            return this;
        }

        /**
         * Creates the file if needed, loads it, and returns a handle.
         *
         * @return a handle on the loaded config
         * @throws ConfigException if the file cannot be read or written, contains a setting that
         *                         reads as a mistyped declared key, or fails validation
         */
        public ConfigHandle<T> load() throws ConfigException {
            final EnvOverlay overlay = EnvOverlay.forSpec(specType, envPrefix, environment, gson);
            final ConfigHandle<T> handle = new ConfigHandle<>(file, specType, gson, overlay, validator, onLoad);
            handle.loadInitially();
            return handle;
        }
    }
}
