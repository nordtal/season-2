package xyz.growaction.javacore.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import xyz.growaction.javacore.config.exception.ConfigException;
import xyz.growaction.javacore.config.exception.ConfigInitializationException;
import xyz.growaction.javacore.config.exception.ConfigWriteException;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

/**
 * Class for loading/saving JSON config files using {@link JsonConfig}
 *
 * @see JsonConfig
 */
public class JsonConfigLoader {

    private static final Logger LOG = LoggerFactory.getLogger(JsonConfigLoader.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    public static <T extends JsonConfig> @NotNull T load(final @NotNull File configFile, final @NotNull Class<T> configClass) throws ConfigException {
        if (!configFile.exists()) {
            createDefaultConfig(configFile, configClass);
        }

        //TODO: implementation
        return null;
    }

    /**
     * Creates a default config from a {@link JsonConfig} class
     *
     * @param configFile the {@link File} to write the config to
     * @param configClass the {@link Class} of {@code T}
     * @param <T> generic describing the implemented type of {@link JsonConfig}
     * @throws ConfigInitializationException if the class does not have a no-args-constructor or it cannot be accessed
     * @throws ConfigWriteException in case of an error converting the {@code T} to JSON or writing the {@link File}
     */
    private static <T extends JsonConfig> void createDefaultConfig(final @NotNull File configFile, final @NotNull Class<T> configClass) throws ConfigInitializationException, ConfigWriteException {
        final T defaultInstance = createDefaultInstance(configClass);

        writeInstanceToFile(configFile, configClass, defaultInstance);
    }

    /**
     * Writes an instance of {@link JsonConfig} to a {@link File}
     *
     * @param configFile  the {@link File} to write to
     * @param configClass the {@link Class} of {@code T}
     * @param instance    the instance of the config class to save as JSON
     * @param <T>         generic describing the implemented type of {@link JsonConfig}
     * @throws ConfigWriteException in case of an error converting the {@code T} to JSON or writing the {@link File}
     */
    private static <T extends JsonConfig> void writeInstanceToFile(final @NotNull File configFile, final @NotNull Class<T> configClass, final @NotNull T instance) throws ConfigWriteException {
        try {
            if (configFile.getParentFile().mkdirs() || configFile.createNewFile()) {
                LOG.info("Created config file {} for class type {}", configFile.getName(), configClass.getSimpleName());
            }

            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(configFile, instance);
        } catch (IOException e) {
            throw new ConfigWriteException(configClass, e);
        }
    }

    /**
     * Creates a default instance of the given {@link JsonConfig} class
     *
     * @param configClass the class of {@code T}
     * @return the default instance of {@code T} using the no-args-constructor
     * @param <T>         generic describing the implemented type of {@link JsonConfig}
     * @throws ConfigInitializationException if the class does not have a no-args-constructor or it cannot be accessed
     */
    private static <T extends JsonConfig> T createDefaultInstance(final @NotNull Class<T> configClass) throws ConfigInitializationException {
        try {
            return configClass.getDeclaredConstructor().newInstance();
        } catch (InstantiationException | NoSuchMethodException | InvocationTargetException |
                 IllegalAccessException e) {
            throw new ConfigInitializationException(configClass, e);
        }
    }

}