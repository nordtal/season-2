package eu.nordtal.jcore.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.config.internal.EnvOverlay;
import eu.nordtal.jcore.config.spec.SpecAdapterFactory;
import eu.nordtal.jcore.config.spec.Specs;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Loads commented YAML configuration files described by an annotated interface.
 *
 * <pre>{@code
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
 * }</pre>
 *
 * The file is created with its defaults and comments if it does not exist, kept in step with the
 * interface on every load, and refused outright if it contains a setting the interface does not
 * declare. Any single setting can be overridden by an environment variable - see
 * {@link EnvOverlay}.
 *
 * @see ConfigHandle
 */
public final class ConfigLoader {

    /** The organisation-wide environment variable prefix. */
    public static final String DEFAULT_ENV_PREFIX = "NORDTAL";

    /**
     * The Gson every config is serialized through.
     * <p>
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

    private ConfigLoader() {
    }

    /**
     * A Gson pre-configured for Spec, to add your own type adapters to.
     *
     * @return a builder that already has the Spec adapter factory and the number policy set
     */
    public static @NotNull GsonBuilder gsonBuilder() {
        return new GsonBuilder()
                .registerTypeAdapterFactory(SpecAdapterFactory.INSTANCE)
                .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE);
    }

    /**
     * Loads a configuration with the defaults: the {@code NORDTAL} environment prefix, no
     * validation and no load hook.
     *
     * @throws ConfigException if the file cannot be read or written, or contains an unknown key
     */
    public static @NotNull <T> ConfigHandle<T> load(final @NotNull Path file,
                                                    final @NotNull Class<T> specType) throws ConfigException {
        return builder(file, specType).load();
    }

    /** Starts building a loader for {@code file}. */
    public static @NotNull <T> Builder<T> builder(final @NotNull Path file, final @NotNull Class<T> specType) {
        return new Builder<>(file, specType);
    }

    /** Starts building a loader for {@code file}. */
    public static @NotNull <T> Builder<T> builder(final @NotNull File file, final @NotNull Class<T> specType) {
        return new Builder<>(file.toPath(), specType);
    }

    /** Configures and creates a {@link ConfigHandle}. */
    public static final class Builder<T> {

        private final Path file;
        private final Class<T> specType;
        private Gson gson = DEFAULT_GSON;
        private String envPrefix = DEFAULT_ENV_PREFIX;
        private Function<String, String> environment = System::getenv;
        private ConfigValidator<T> validator = config -> {
        };
        private Consumer<T> onLoad = config -> {
        };

        private Builder(final Path file, final Class<T> specType) {
            if (!Specs.isConfigSpec(specType)) {
                throw new IllegalArgumentException(
                        specType.getName() + " must be an interface annotated with @ConfigSpec.");
            }
            // A spec is served by a java.lang.reflect.Proxy, and the proxy reflects on the
            // interface's own methods. A non-public interface passes construction and then fails
            // with an UndeclaredThrowableException wrapping IllegalAccessException the first time
            // a value is read - a long way from the cause. Say so here instead.
            if (!Modifier.isPublic(specType.getModifiers())) {
                throw new IllegalArgumentException(
                        specType.getName() + " must be public. A config spec is served by a "
                                + "reflective proxy, which cannot read a package-private interface.");
            }
            this.file = file;
            this.specType = specType;
        }

        /**
         * Uses a custom Gson, for configs with their own value types. Build it from
         * {@link ConfigLoader#gsonBuilder()} so the Spec adapter and number policy stay in place.
         */
        public @NotNull Builder<T> gson(final @NotNull Gson gson) {
            this.gson = gson;
            return this;
        }

        /** Changes the environment variable prefix. Defaults to {@code NORDTAL}. */
        public @NotNull Builder<T> envPrefix(final @NotNull String envPrefix) {
            this.envPrefix = envPrefix;
            return this;
        }

        /** Replaces the source of environment variables. Intended for tests. */
        public @NotNull Builder<T> environment(final @NotNull Function<String, String> environment) {
            this.environment = environment;
            return this;
        }

        /** Turns the environment overlay off entirely. */
        public @NotNull Builder<T> withoutEnvironmentOverlay() {
            this.environment = name -> null;
            return this;
        }

        /**
         * Checks the loaded values. Runs on every load and reload, after the environment overlay
         * is applied, so it also covers values that came from the environment.
         */
        public @NotNull Builder<T> validator(final @NotNull ConfigValidator<T> validator) {
            this.validator = validator;
            return this;
        }

        /**
         * Runs after every successful load and reload - <b>unconditionally</b>, whether or not
         * the file changed. This is the replacement for {@code JsonConfig#postLoad()}, which only
         * ran when the loader had found a difference and so, for a file that already matched the
         * class, never ran at all.
         */
        public @NotNull Builder<T> onLoad(final @NotNull Consumer<T> onLoad) {
            this.onLoad = onLoad;
            return this;
        }

        /**
         * Creates the file if needed, loads it, and returns a handle.
         *
         * @throws ConfigException if the file cannot be read or written, contains a setting the
         *                         spec does not declare, or fails validation
         */
        public @NotNull ConfigHandle<T> load() throws ConfigException {
            final EnvOverlay overlay = EnvOverlay.forSpec(specType, envPrefix, environment, gson);
            final ConfigHandle<T> handle =
                    new ConfigHandle<>(file, specType, gson, overlay, validator, onLoad);
            handle.loadInitially();
            return handle;
        }
    }
}
