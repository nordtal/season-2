package xyz.growaction.javacore.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.growaction.javacore.config.exception.ConfigInitializationException;

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

    public static <T extends JsonConfig> @NotNull T load(final @NotNull File configFile, final @NotNull Class<T> configClass) throws ConfigInitializationException {
        if (!configFile.exists()) {
            createDefaultConfig(configFile, configClass);
        }
    }

    private static <T extends JsonConfig> void createDefaultConfig(final @NotNull File configFile, final @NotNull Class<T> configClass) throws ConfigInitializationException {
        final T defaultInstance = createDefaultInstance(configClass);
        try {
            if (configFile.getParentFile().mkdirs() || configFile.createNewFile()) {
                LOG.info("Created default config file {} for class type {}", configFile.getName(), configClass.getSimpleName());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


    }

    private static <T extends JsonConfig> void writeInstanceToFile(final @NotNull File configFile, final @NotNull Class<T> configClass, final @NotNull T instance) {
        try {
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(configFile, instance);
        } catch (IOException e) {
            throw new ;
        }
    }

    private static <T extends JsonConfig> T createDefaultInstance(final @NotNull Class<T> configClass) throws ConfigInitializationException {
        try {
            return configClass.getDeclaredConstructor().newInstance();
        } catch (InstantiationException | NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            throw new ConfigInitializationException(configClass, e);
        }
    }

}
