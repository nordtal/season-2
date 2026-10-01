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
    void stewardYmlsBunqTokenIsDeclaredSecretInItsSchema() {
        final SchemaNode bunq = SchemaWriter.build(StewardSpec.class).children().get("bunq");
        assertNotNull(bunq, "StewardSpec's schema has no 'bunq' entry at all - this is where its guard lives");
        final SchemaNode token = bunq.children().get("token");
        assertNotNull(token, "BunqSpec's schema has no 'token' entry at all");
        assertTrue(token.secret(), "bunq.token opens steward-bunq, the bank's one door, and must be @Secret");
    }

    @Test
    void stewardYmlsAgentTokenIsDeclaredSecretInItsSchema() {
        final SchemaNode agent =
                SchemaWriter.build(StewardSpec.class).children().get("agent");
        assertNotNull(agent, "StewardSpec's schema has no 'agent' entry at all");
        final SchemaNode token = agent.children().get("token");
        assertNotNull(token, "AgentSpec's schema has no 'token' entry at all");
        assertTrue(
                token.secret(),
                "agent.token is the shared secret sent to steward-agent as"
                        + " X-Steward-Token and must be @Secret - without the annotation it is masked only"
                        + " by ConfigEntry.isSecretKey's 'token' substring heuristic, which a rename of this"
                        + " key would silently break");
    }
}
