package xyz.growaction.javacore.config;

import com.fasterxml.jackson.databind.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import xyz.growaction.javacore.config.exception.ConfigException;
import xyz.growaction.javacore.config.exception.ConfigInitializationException;
import xyz.growaction.javacore.config.exception.ConfigReadException;
import xyz.growaction.javacore.config.exception.ConfigWriteException;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Class for loading/saving JSON config files using {@link JsonConfig}
 *
 * @see JsonConfig
 */
public class JsonConfigLoader {

    private static final Logger LOG = LoggerFactory.getLogger(JsonConfigLoader.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /**
     * Loads a {@link T} from a {@link File} and creates a default config
     * <p>
     *     For details see {@link JsonConfigLoader#loadConfig(File, Class)}.
     * </p>
     *
     * @param configFile the {@link File} to load the config from
     * @param configClass the {@link Class} of {@link T}
     * @return the loaded instance of {@link T}
     * @param <T>         generic describing the implemented type of {@link JsonConfig}
     * @throws ConfigException in case of an exception while reading, parsing or saving the JSON
     */
    public static @NotNull <T extends JsonConfig> T load(final @NotNull File configFile, final @NotNull Class<T> configClass) throws ConfigException {
        if (!configFile.exists()) {
            createDefaultConfig(configFile, configClass);
        }

        return loadConfig(configFile, configClass);
    }

    /**
     * Saves a {@link JsonConfig} to a {@link File}
     *
     * @param configFile the {@link File} to save to
     * @param config the {@link JsonConfig} instance to save
     * @throws ConfigWriteException in case of an exception while parsing the JSON or writing to the {@link File}
     */
    public static void save(final @NotNull File configFile, final @NotNull JsonConfig config) throws ConfigWriteException {
        writeInstanceToFile(configFile, config);
    }

    /**
     * Loads a {@link T} from a {@link File}
     * <p>
     *     In the process, the JSON from the {@link File} is parsed onto a fresh of instance of {@link T}, which is then
     *     parsed back into a {@link JsonNode}. The {@link T} is checked for differences and newly added fields and
     *     missing ones are collected and printed to console. If differences were found, the parsed {@link T} is saved
     *     to remove missing fields and add the defaults of new ones to the {@link File}.
     * </p>
     *
     * @param configFile the {@link File} storing the JSON
     * @param configClass the {@link Class} of {@link T}
     * @return the loaded instance of {@link T}
     * @param <T>         generic describing the implemented type of {@link JsonConfig}
     * @throws ConfigException in case of errors while reading, parsing and saving the JSONs
     */
    private static @NotNull <T extends JsonConfig> T loadConfig(final @NotNull File configFile, final @NotNull Class<T> configClass) throws ConfigException {
        final JsonNode savedJson = readJson(configFile);
        final T mergedConfig = readConfig(configFile, configClass);
        final JsonNode mergedJson = configToJson(mergedConfig);

        // Calculate differences
        final Map<String, Boolean> differences = jsonDifferences(savedJson, mergedJson);

        // If no differences were found, return
        if (differences.isEmpty()) {
            return mergedConfig;
        }

        // Print and save differences
        writeInstanceToFile(configFile, mergedConfig);
        LOG.info("Config file '{}' has been updated", configFile.getName());
        printDifferences(configFile.getName(), differences);

        return mergedConfig;
    }

    /**
     * Reads a {@link File} as {@link T}
     *
     * @param configFile  the {@link File} to read
     * @param configClass the {@link Class} of {@link T}
     * @return the parsed {@link T}
     * @param <T>         generic describing the implemented type of {@link JsonConfig}
     * @throws ConfigReadException in case of an error reading the JSON
     */
    private static @NotNull <T extends JsonConfig> T readConfig(final @NotNull File configFile, final @NotNull Class<T> configClass) throws ConfigReadException {
        try {
            return OBJECT_MAPPER.readValue(configFile, configClass);
        } catch (IOException e) {
            throw new ConfigReadException(String.format("Error reading JSON config file '%s' to class [%s]", configFile.getName(), configClass.getName()), e);
        }
    }

    /**
     * Reads a {@link File} as {@link JsonNode}
     *
     * @param configFile the {@link File} to read
     * @return the parsed {@link JsonNode}
     * @throws ConfigReadException in case of an error reading the JSON
     */
    private static @NotNull JsonNode readJson(final @NotNull File configFile) throws ConfigReadException {
        try {
            return OBJECT_MAPPER.readTree(configFile);
        } catch (IOException e) {
            throw new ConfigReadException(String.format("Error reading JSON config file '%s' to JsonNode", configFile.getName()), e);
        }
    }

    /**
     * Converts a {@link JsonConfig} instance to a {@link JsonConfig}
     *
     * @param jsonConfig the {@link JsonConfig} instance to convert
     * @return the {@link JsonNode}
     */
    private static @NotNull JsonNode configToJson(final @NotNull JsonConfig jsonConfig) {
        return OBJECT_MAPPER.valueToTree(jsonConfig);
    }

    /**
     * Prints the results from a differences {@link Map} to the {@link JsonConfigLoader#LOG}
     *
     * @param fileName    the name of the {@link File} the differences have been calculated for
     * @param differences the {@link Map} of differences in the file structure
     * @see JsonConfigLoader#jsonDifferences(JsonNode, JsonNode)
     */
    private static void printDifferences(final @NotNull String fileName, final @NotNull Map<String, Boolean> differences) {
        if (differences.containsValue(false)) {
            LOG.info(
                    "The following fields were redundant and have been removed from '{}':\n{}",
                    fileName,
                    differences.entrySet().stream()
                            .filter(entry -> !entry.getValue())
                            .map(entry -> String.format("\t- %s", entry.getKey()))
                            .collect(Collectors.joining())
            );
        }
        if (differences.containsValue(true)) {
            LOG.info(
                    "The following fields have been added to '{}':\n{}",
                    fileName,
                    differences.entrySet().stream()
                            .filter(Map.Entry::getValue)
                            .map(entry -> String.format("\t- %s", entry.getKey()))
                            .collect(Collectors.joining())
            );
        }
    }

    /**
     * Recursively collects the differences of an old and a new {@link JsonNode} into a {@link Map} of property paths and
     * {@code true} if they have been added or {@code false} if they are missing
     *
     * @param oldJson the old {@link JsonNode}
     * @param newJson the new {@link JsonNode} to compare the old one to
     * @return a {@link Map} of property path {@link String}s and a {@link Boolean} describing whether they have been added or removed
     * @see JsonConfigLoader#jsonDifferencesRecursive(JsonNode, JsonNode, String, Map)
     */
    private static @NotNull Map<String, Boolean> jsonDifferences(final @NotNull JsonNode oldJson, @NotNull final JsonNode newJson) {
        final Map<String, Boolean> differences = new HashMap<>();
        jsonDifferencesRecursive(oldJson, newJson, "", differences);
        return differences;
    }

    /**
     * Recursively collects the differences of an old and a new {@link JsonNode} into a {@link Map} of property paths and
     * {@code true} if they have been added or {@code false} if they are missing
     *
     * @param oldNode     the old {@link JsonNode}
     * @param newNode     the new {@link JsonNode} to compare the old one to
     * @param path        keeps track of the property path when descending in the recursive stack, should be an empty {@link String} when calling this function
     * @param differences the {@link Map} to write the differences to
     * @see JsonConfigLoader#jsonDifferences(JsonNode, JsonNode)
     */
    private static void jsonDifferencesRecursive(final @NotNull JsonNode oldNode, final @NotNull JsonNode newNode, final @NotNull String path, final @NotNull Map<String, Boolean> differences) {
        Set<String> oldFields = new HashSet<>();
        oldNode.fieldNames().forEachRemaining(oldFields::add);

        Set<String> newFields = new HashSet<>();
        newNode.fieldNames().forEachRemaining(newFields::add);

        // Find missing fields
        for (String key : oldFields) {
            if (!newFields.contains(key)) {
                differences.put(path + key, false);
            }
        }

        // Find newly added fields
        for (String key : newFields) {
            if (!oldFields.contains(key)) {
                differences.put(path + key, true);
            } else {
                JsonNode oldChild = oldNode.get(key);
                JsonNode newChild = newNode.get(key);
                if (oldChild.isObject() && newChild.isObject()) {
                    jsonDifferencesRecursive(oldChild, newChild, path + key + ".", differences);
                }
            }
        }
    }

    /**
     * Creates a default config from a {@link T} class if the provided {@link File} does not exist
     *
     * @param configFile  the {@link File} to write the config to
     * @param configClass the {@link Class} of {@link T}
     * @param <T>         generic describing the implemented type of {@link JsonConfig}
     * @throws ConfigInitializationException if the class does not have a no-args-constructor, or it cannot be accessed
     * @throws ConfigWriteException          in case of an error converting the {@link T} to JSON or writing the {@link File}
     */
    private static <T extends JsonConfig> void createDefaultConfig(final @NotNull File configFile, final @NotNull Class<T> configClass) throws ConfigInitializationException, ConfigWriteException {
        if (configFile.exists()) {
            return;
        }

        final T defaultInstance = createDefaultInstance(configClass);
        writeInstanceToFile(configFile, defaultInstance);
    }

    /**
     * Writes an instance of {@link JsonConfig} to a {@link File}
     *
     * @param configFile the {@link File} to write to
     * @param instance   the instance of the {@link JsonConfig} class to save as JSON
     * @throws ConfigWriteException in case of an error converting the {@link JsonConfig} to JSON or writing the {@link File}
     */
    private static void writeInstanceToFile(final @NotNull File configFile, final @NotNull JsonConfig instance) throws ConfigWriteException {
        try {
            if (configFile.getParentFile().mkdirs() || configFile.createNewFile()) {
                LOG.info("Created config file {} for class type {}", configFile.getName(), instance.getClass().getSimpleName());
            }

            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(configFile, instance);
        } catch (IOException e) {
            throw new ConfigWriteException(configFile, instance.getClass(), e);
        }
    }

    /**
     * Creates a default instance of the given {@link T} class
     *
     * @param configClass the class of {@link T}
     * @param <T>         generic describing the implemented type of {@link JsonConfig}
     * @return the default instance of {@link T} using the no-args-constructor
     * @throws ConfigInitializationException if the class does not have a no-args-constructor, or it cannot be accessed
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