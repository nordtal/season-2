package eu.nordtal.s2.discordbot.config;

import eu.nordtal.jcore.config.schema.SchemaNode;
import eu.nordtal.jcore.config.schema.SchemaWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * steward/70: a credential that is not declared {@code @Secret} is not masked by the schema at
 * all - it is only masked by steward-worker's leaf-key heuristic
 * ({@code ConfigEntry.isSecretKey}, which matches {@code token}/{@code password}/{@code key}/
 * {@code secret} substrings and stays in force underneath either way). That heuristic already
 * covers every credential this module writes, which is why nothing is unmasked in the browser
 * today - but the schema itself said nothing about any of them, because none of these getters
 * carried the annotation. This is the explicit declaration and the one steward/58's search
 * exclusion is defined to key off; the heuristic is not a substitute for it.
 *
 * <p>Deliberately built straight from the {@code @ConfigSpec} interfaces with
 * {@link SchemaWriter#build}, not through {@link Configs}: whether the annotation is present is a
 * property of the interface alone, and does not need a file on disk to demonstrate.</p>
 */
class SchemaSecretsTest {

    @Test
    @DisplayName("bot.yml's token is declared @Secret in its schema")
    void tokenIsSecret() {
        final SchemaNode token = SchemaWriter.build(BotSpec.class).children().get("token");
        assertNotNull(token, "BotSpec's schema has no 'token' entry at all");
        assertTrue(token.secret(), "bot.yml's token is a Discord bot token and must be @Secret");
    }

    @Test
    @DisplayName("bot.yml's bunq.api-key is declared @Secret in its schema")
    void bunqApiKeyIsSecret() {
        final SchemaNode bunq = SchemaWriter.build(BotSpec.class).children().get("bunq");
        assertNotNull(bunq, "BotSpec's schema has no 'bunq' entry at all");
        final SchemaNode apiKey = bunq.children().get("api-key");
        assertNotNull(apiKey, "BunqSpec's schema has no 'api-key' entry at all");
        assertTrue(apiKey.secret(), "bunq.api-key is a bunq API credential and must be @Secret");
    }

    @Test
    @DisplayName("database.yml's password is declared @Secret in its schema")
    void databasePasswordIsSecret() {
        final SchemaNode password = SchemaWriter.build(DatabaseSpec.class).children().get("password");
        assertNotNull(password, "DatabaseSpec's schema has no 'password' entry at all");
        assertTrue(password.secret(), "database.yml's password must be @Secret");
    }
}
