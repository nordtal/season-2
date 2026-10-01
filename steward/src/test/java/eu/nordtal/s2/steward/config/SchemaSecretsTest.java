package eu.nordtal.s2.steward.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.schema.SchemaNode;
import eu.nordtal.jcore.config.schema.SchemaWriter;
import org.junit.jupiter.api.Test;

/**
 * The credentials {@code steward.yml} carries, and the annotation that keeps them out of a browser.
 *
 * {@code ConfigEntry.isSecretKey} only guesses from a name; {@code @Secret} is a statement about the value.
 */
class SchemaSecretsTest {

    @Test
    void stewardYmlsBunqApiKeyIsDeclaredSecretInItsSchema() {
        final SchemaNode bunq = SchemaWriter.build(StewardSpec.class).children().get("bunq");
        assertNotNull(bunq, "StewardSpec's schema has no 'bunq' entry at all - this is where its guard lives");
        final SchemaNode apiKey = bunq.children().get("api-key");
        assertNotNull(apiKey, "BunqSpec's schema has no 'api-key' entry at all");
        assertTrue(apiKey.secret(), "bunq.api-key is a bunq API credential and must be @Secret");
    }

    @Test
    void stewardYmlsDeployerTokenIsDeclaredSecretInItsSchema() {
        final SchemaNode deployer =
                SchemaWriter.build(StewardSpec.class).children().get("deployer");
        assertNotNull(deployer, "StewardSpec's schema has no 'deployer' entry at all");
        final SchemaNode token = deployer.children().get("token");
        assertNotNull(token, "DeployerSpec's schema has no 'token' entry at all");
        assertTrue(
                token.secret(),
                "deployer.token is the shared secret sent to steward-deployer as"
                        + " X-Steward-Token and must be @Secret - without the annotation it is masked only"
                        + " by ConfigEntry.isSecretKey's 'token' substring heuristic, which a rename of this"
                        + " key would silently break");
    }
}
