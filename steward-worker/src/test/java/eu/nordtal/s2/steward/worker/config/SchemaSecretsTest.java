package eu.nordtal.s2.steward.worker.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.schema.SchemaNode;
import eu.nordtal.jcore.config.schema.SchemaWriter;
import org.junit.jupiter.api.Test;

/**
 * The credentials {@code steward.yml} carries, and the annotation that keeps them out of a browser.
 *
 * Every credential in this schema needs its own assertion here: moving a credential without moving its guard is a
 * change that costs nothing at the time and everything later, with nobody asserting anything about the new setting.
 *
 * What the annotation actually buys: A value that is not declared {@code @Secret} is not masked by the schema at
 * all. It is still masked by the leaf-key heuristic in this module's own {@code ConfigEntry.isSecretKey} - which
 * matches {@code token}, {@code password}, {@code key} and {@code secret} as substrings, and would catch
 * {@code api-key} by accident - and that heuristic stays in force underneath either way. It is not a substitute: the
 * heuristic is a guess about a name, the annotation is a statement about a value.
 *
 * Built straight from the {@code @ConfigSpec} interface with {@link SchemaWriter#build} rather than through
 * {@link Configs}: whether the annotation is present is a property of the interface alone and needs no file on disk.
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
    void stewardYmlsApiTokenIsDeclaredSecretInItsSchema() {
        final SchemaNode api = SchemaWriter.build(StewardSpec.class).children().get("api");
        assertNotNull(api, "StewardSpec's schema has no 'api' entry at all");
        final SchemaNode token = api.children().get("token");
        assertNotNull(token, "ApiSpec's schema has no 'token' entry at all");
        assertTrue(
                token.secret(),
                "api.token is the shared secret steward-ui sends as"
                        + " X-Steward-Token and must be @Secret - without the annotation it is masked only"
                        + " by ConfigEntry.isSecretKey's 'token' substring heuristic, which a rename of this"
                        + " key would silently break");
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
