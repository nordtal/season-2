package eu.nordtal.s2.discordbot.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.schema.SchemaNode;
import eu.nordtal.jcore.config.schema.SchemaWriter;
import org.junit.jupiter.api.Test;

/**
 * A credential that is not declared {@code @Secret} is not masked by the schema at all.
 *
 * It is only masked by steward-worker's leaf-key heuristic ( {@code ConfigEntry.isSecretKey}, which matches
 * {@code token} / {@code password} / {@code key} / {@code secret} substrings and stays in force underneath
 * either way).
 *
 * The explicit declaration is what a schema search exclusion keys off; the heuristic is not a substitute for it.
 *
 * Deliberately built straight from the {@code @ConfigSpec} interfaces with {@link SchemaWriter#build}, not through
 * {@link Configs}: whether the annotation is present is a property of the interface alone, and does not need a file
 * on disk to demonstrate.
 */
class SchemaSecretsTest {

    @Test
    void botYmlsTokenIsDeclaredSecretInItsSchema() {
        final SchemaNode token = SchemaWriter.build(BotSpec.class).children().get("token");
        assertNotNull(token, "BotSpec's schema has no 'token' entry at all");
        assertTrue(token.secret(), "bot.yml's token is a Discord bot token and must be @Secret");
    }

    @Test
    void botYmlDeclaresNoBunqCredentialAtAllAnyMore() {
        // Asserted in the negative: re-adding a bunq block to BotSpec would put a bank credential here, unannotated.
        assertNull(
                SchemaWriter.build(BotSpec.class).children().get("bunq"),
                "BotSpec declares a bunq block again. The key belongs to steward-worker - see"
                        + " StewardSpec.BunqSpec and steward-worker's SchemaSecretsTest.");
    }

    @Test
    void databaseYmlsPasswordIsDeclaredSecretInItsSchema() {
        final SchemaNode password =
                SchemaWriter.build(DatabaseSpec.class).children().get("password");
        assertNotNull(password, "DatabaseSpec's schema has no 'password' entry at all");
        assertTrue(password.secret(), "database.yml's password must be @Secret");
    }
}
