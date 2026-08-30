package eu.nordtal.s2.accessbot.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.ConfigValidator;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.config.exception.ConfigReadException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Where the bot's config files live, and the one place that knows they used to be JSON.
 * <p>
 * Each file gets its own environment namespace - {@code NORDTAL_DATABASE_*},
 * {@code NORDTAL_BOT_*}, {@code NORDTAL_PAYMENT_PROCESSING_*}. A single shared {@code NORDTAL}
 * prefix would make generic keys collide across files: {@code password} in {@code database.yml}
 * and a {@code password} in any other config would both be {@code NORDTAL_PASSWORD}.
 */
@Slf4j
public final class Configs {

    /**
     * Where the config files live. Mounted as a Docker volume; see the module Dockerfile.
     * <p>
     * Overridable with {@code -Daccess.config.dir=...} so the tests can point it at a
     * temporary directory. Nothing in production sets it.
     */
    static final String DIRECTORY_PROPERTY = "access.config.dir";

    private static final Gson JSON = new Gson();

    private Configs() {
    }

    private static Path directory() {
        return Path.of(System.getProperty(DIRECTORY_PROPERTY, "config"));
    }

    // ------------------------------------------------------------------ the three configs

    public static @NotNull ConfigHandle<DatabaseSpec> database() throws ConfigException {
        return load("database", DatabaseSpec.class, "NORDTAL_DATABASE", Map.of(), config -> {
            requireText("jdbc-url", config.jdbcUrl());
            requireText("username", config.username());
            if (!config.jdbcUrl().startsWith("jdbc:postgresql:")) {
                throw new IllegalArgumentException(
                        "jdbc-url must be a PostgreSQL URL (jdbc:postgresql://host:port/database)");
            }
            if (config.maximumPoolSize() < 1) {
                throw new IllegalArgumentException("maximum-pool-size must be at least 1");
            }
        });
    }

    public static @NotNull ConfigHandle<BotSpec> bot() throws ConfigException {
        return load("bot", BotSpec.class, "NORDTAL_BOT", Map.of(), config -> {
            requireSecret("token", "NORDTAL_BOT_TOKEN", config.token());
            requireSecret("bunq.api-key", "NORDTAL_BOT_BUNQ_API_KEY", config.bunq().apiKey());
            requireSecret("bunq.account-id", "NORDTAL_BOT_BUNQ_ACCOUNT_ID", config.bunq().accountId());
            try {
                Long.parseLong(config.bunq().accountId().trim());
            } catch (NumberFormatException e) {
                // The old code called Long.parseLong inside the poll loop, so a wrong value
                // surfaced as a NumberFormatException minutes into a run.
                throw new IllegalArgumentException("bunq.account-id must be a number");
            }
        });
    }

    public static @NotNull ConfigHandle<PaymentProcessingSpec> paymentProcessing() throws ConfigException {
        // The two balance settings were flat in the JSON file and are nested now, so the plain
        // underscore-to-hyphen rule is not enough to convert them.
        final Map<String, String> renames = Map.of(
                "balance_channel_id", "balance.channel-id",
                "balance_channel_format", "balance.name-format");

        return load("payment-processing", PaymentProcessingSpec.class, "NORDTAL_PAYMENT_PROCESSING",
                renames, config -> {
                    if (config.checkIntervalSeconds() <= 0) {
                        throw new IllegalArgumentException(
                                "check-interval-seconds must be greater than zero, was "
                                        + config.checkIntervalSeconds());
                    }
                    requireText("confirmation-channel-id", config.confirmationChannelId());
                    requireText("balance.channel-id", config.balance().channelId());
                    if (!config.balance().nameFormat().contains("%s")) {
                        throw new IllegalArgumentException(
                                "balance.name-format must contain %s, where the balance goes");
                    }
                });
    }

    // ------------------------------------------------------------------ loading

    private static <T> ConfigHandle<T> load(final String name, final Class<T> specType,
                                            final String envPrefix, final Map<String, String> renames,
                                            final ConfigValidator<T> validator) throws ConfigException {
        final Path file = directory().resolve(name + ".yml");
        convertLegacyJson(name, file, renames);

        final boolean fresh = !Files.isRegularFile(file);
        final ConfigHandle<T> handle = ConfigLoader.builder(file, specType)
                .envPrefix(envPrefix)
                .validator(validator)
                .load();

        if (fresh) {
            log.warn("No config existed at {} - defaults were written and are almost certainly "
                    + "not what you want", file.toAbsolutePath());
        }
        return handle;
    }

    /**
     * Converts a jcore 1.x {@code config/<name>.json} to {@code config/<name>.yml} once, the
     * first time the new bot starts.
     * <p>
     * The alternative was a documented manual step, which was rejected: the production config
     * lives in a Docker volume that nobody edits between pulling an image and starting it, so a
     * manual step would in practice mean the bot starting on defaults - and after this change,
     * refusing to start at all. Converting keeps the operator's values and their meaning.
     * <p>
     * The JSON file is renamed rather than deleted, so nothing is lost if the conversion turns
     * out to have got something wrong.
     */
    private static void convertLegacyJson(final String name, final Path yaml,
                                          final Map<String, String> renames) throws ConfigException {
        final Path json = directory().resolve(name + ".json");
        if (Files.isRegularFile(yaml) || !Files.isRegularFile(json)) {
            return;
        }

        log.info("Found a jcore 1.x config at {}. Converting it to {}.", json, yaml.getFileName());
        try {
            final JsonElement root = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                throw new ConfigReadException(json + " is not a JSON object; convert it by hand.", null);
            }

            final Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                final String target = renames.getOrDefault(entry.getKey(), kebab(entry.getKey()));
                put(converted, target, JSON.fromJson(entry.getValue(), Object.class));
            }

            // Written as JSON, which is valid YAML, so the real loader parses it on the next line
            // and immediately rewrites it properly, with comments and the header.
            Files.createDirectories(yaml.toAbsolutePath().getParent());
            Files.writeString(yaml, JSON.toJson(converted), StandardCharsets.UTF_8);
            Files.move(json, json.resolveSibling(json.getFileName() + ".migrated"),
                    StandardCopyOption.REPLACE_EXISTING);

            log.info("Converted {} settings. The old file is kept as {}.json.migrated",
                    converted.size(), name);
        } catch (IOException | RuntimeException e) {
            throw new ConfigReadException("Could not convert " + json + " to YAML. Convert it by "
                    + "hand, or move it aside to start with defaults.", e);
        }
    }

    /** {@code check_interval_seconds} -> {@code check-interval-seconds}. */
    private static String kebab(final String snakeCase) {
        return snakeCase.replace('_', '-').toLowerCase(Locale.ROOT);
    }

    /** Writes a possibly dotted path into a nested map. */
    @SuppressWarnings("unchecked")
    private static void put(final Map<String, Object> root, final String path, final Object value) {
        final String[] segments = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            current = (Map<String, Object>) current
                    .computeIfAbsent(segments[i], key -> new LinkedHashMap<String, Object>());
        }
        current.put(segments[segments.length - 1], value);
    }

    // ------------------------------------------------------------------ validation helpers

    private static void requireText(final String key, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be empty");
        }
    }

    private static void requireSecret(final String key, final String variable, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    key + " is empty. Set " + variable + " in the environment.");
        }
    }
}
