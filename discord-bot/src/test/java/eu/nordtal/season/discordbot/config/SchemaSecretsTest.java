package eu.nordtal.season.discordbot.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.spec.schema.SchemaNode;
import eu.nordtal.season.spec.schema.SchemaWriter;
import org.junit.jupiter.api.Test;

/**
 * Every credential in a bot config is declared {@code @Secret}, which is what masks it in the schema.
 *
 * Built from the {@code @ConfigSpec} interfaces directly, since the annotation is a property of the interface alone.
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
        // Asserted in the negative: a bunq block in BotSpec would be an unannotated bank credential.
        assertNull(
                SchemaWriter.build(BotSpec.class).children().get("bunq"),
                "BotSpec declares a bunq block again. The key belongs to steward - see"
                        + " StewardSpec.BunqSpec and steward's SchemaSecretsTest.");
    }

    @Test
    void databaseYmlsPasswordIsDeclaredSecretInItsSchema() {
        final SchemaNode password =
                SchemaWriter.build(DatabaseSpec.class).children().get("password");
        assertNotNull(password, "DatabaseSpec's schema has no 'password' entry at all");
        assertTrue(password.secret(), "database.yml's password must be @Secret");
    }
}
