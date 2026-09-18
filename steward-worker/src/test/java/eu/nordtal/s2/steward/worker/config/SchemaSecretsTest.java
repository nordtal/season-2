package eu.nordtal.s2.steward.worker.config;

import eu.nordtal.jcore.config.schema.SchemaNode;
import eu.nordtal.jcore.config.schema.SchemaWriter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The credentials {@code steward.yml} carries, and the annotation that keeps them out of a browser.
 *
 * <h2>Why this file exists here (steward/109)</h2>
 * The assertion on {@code bunq.api-key} used to live in {@code discord-bot}'s
 * {@code SchemaSecretsTest}, because the key used to live in {@code bot.yml}. Moving a credential
 * without moving its guard is the kind of change that costs nothing at the time and everything
 * later: the old test would have been deleted along with the old setting, the new setting would
 * have arrived with nobody asserting anything about it, and <b>nothing would have gone red</b>.
 *
 * <h2>What the annotation actually buys</h2>
 * A value that is not declared {@code @Secret} is not masked by the schema at all. It is still
 * masked by the leaf-key heuristic in this module's own {@code ConfigEntry.isSecretKey} - which
 * matches {@code token}, {@code password}, {@code key} and {@code secret} as substrings, and would
 * catch {@code api-key} by accident - and that heuristic stays in force underneath either way. It
 * is not a substitute: the heuristic is a guess about a name, the annotation is a statement about a
 * value, and steward/58's search exclusion keys off the annotation.
 *
 * <p>Built straight from the {@code @ConfigSpec} interface with {@link SchemaWriter#build} rather
 * than through {@link Configs}: whether the annotation is present is a property of the interface
 * alone and needs no file on disk.</p>
 */
class SchemaSecretsTest {

    @Test
    @DisplayName("steward.yml's bunq.api-key is declared @Secret in its schema")
    void bunqApiKeyIsSecret() {
        final SchemaNode bunq = SchemaWriter.build(StewardSpec.class).children().get("bunq");
        assertNotNull(bunq, "StewardSpec's schema has no 'bunq' entry at all - the credential moved"
                + " here in steward/109 and this is where its guard lives");
        final SchemaNode apiKey = bunq.children().get("api-key");
        assertNotNull(apiKey, "BunqSpec's schema has no 'api-key' entry at all");
        assertTrue(apiKey.secret(), "bunq.api-key is a bunq API credential and must be @Secret");
    }

    @Test
    @DisplayName("steward.yml's api.token is declared @Secret in its schema")
    void apiTokenIsSecret() {
        final SchemaNode api = SchemaWriter.build(StewardSpec.class).children().get("api");
        assertNotNull(api, "StewardSpec's schema has no 'api' entry at all");
        final SchemaNode token = api.children().get("token");
        assertNotNull(token, "ApiSpec's schema has no 'token' entry at all");
        assertTrue(token.secret(), "api.token is the shared secret steward-ui sends as"
                + " X-Steward-Token and must be @Secret - without the annotation it is masked only"
                + " by ConfigEntry.isSecretKey's 'token' substring heuristic, which a rename of this"
                + " key would silently break (steward/115)");
    }

    @Test
    @DisplayName("steward.yml's deployer.token is declared @Secret in its schema")
    void deployerTokenIsSecret() {
        final SchemaNode deployer = SchemaWriter.build(StewardSpec.class).children().get("deployer");
        assertNotNull(deployer, "StewardSpec's schema has no 'deployer' entry at all");
        final SchemaNode token = deployer.children().get("token");
        assertNotNull(token, "DeployerSpec's schema has no 'token' entry at all");
        assertTrue(token.secret(), "deployer.token is the shared secret sent to steward-deployer as"
                + " X-Steward-Token and must be @Secret - without the annotation it is masked only"
                + " by ConfigEntry.isSecretKey's 'token' substring heuristic, which a rename of this"
                + " key would silently break (steward/115)");
    }
}
