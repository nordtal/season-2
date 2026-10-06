package eu.nordtal.season.stewardagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import eu.nordtal.season.common.json.Json;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What a service's volumes are handed to before it starts, read from its definition as {@code config} prints it. */
class HandOverTest {

    private final Compose compose =
            new Compose(Path.of("/app/compose.yml"), Path.of("/does/not/exist/.env"), Path.of("/app"), "nordtal-s2");

    @Test
    void aServiceWithAUidIsHandedEveryWritableMountAndNothingReadOnly() {
        final Optional<Compose.Owner> owner = Compose.ownerOf(definition("""
                {"user": "10000:10001", "volumes": [
                  {"type": "bind", "source": "/srv/nordtal/mc-smp", "target": "/data"},
                  {"type": "bind", "source": "/srv/nordtal/mc-smp-plugins", "target": "/data/plugins"},
                  {"type": "volume", "source": "maps", "target": "/maps"},
                  {"type": "bind", "source": "/srv/nordtal/pack", "target": "/pack", "read_only": true},
                  {"type": "tmpfs", "target": "/tmp"}
                ]}"""));

        assertEquals(
                Optional.of(new Compose.Owner("10000", "10001", List.of("/data", "/data/plugins", "/maps"))), owner);
    }

    @Test
    void aUidWithoutAGroupIsItsOwnGroup() {
        final Optional<Compose.Owner> owner = Compose.ownerOf(
                definition("{\"user\": \"10003\", \"volumes\": [{\"type\": \"bind\", \"target\": \"/app/bunq\"}]}"));

        assertEquals(Optional.of(new Compose.Owner("10003", "10003", List.of("/app/bunq"))), owner);
    }

    /** Root needs nothing handed to it, a name cannot be checked against the files and no mount leaves nothing. */
    @Test
    void rootANamedUserAndAServiceWithoutMountsAreLeftAlone() {
        assertTrue(Compose.ownerOf(definition("{\"volumes\": [{\"type\": \"bind\", \"target\": \"/data\"}]}"))
                .isEmpty());
        assertTrue(Compose.ownerOf(
                        definition("{\"user\": \"0:0\", \"volumes\": [{\"type\": \"bind\", \"target\": \"/data\"}]}"))
                .isEmpty());
        assertTrue(Compose.ownerOf(definition(
                        "{\"user\": \"minecraft\", \"volumes\": [{\"type\": \"bind\", \"target\": \"/data\"}]}"))
                .isEmpty());
        assertTrue(Compose.ownerOf(definition("{\"user\": \"10002:10002\"}")).isEmpty());
    }

    /** Root in the service's own image, with only the two capabilities a change of owner needs, never pulling. */
    @Test
    void theHandOverIsAFindAsRootInTheServicesOwnImageThatChangesOnlyWhatIsNotTheUsersYet() {
        final List<String> command =
                compose.handOverCommand("smp", new Compose.Owner("10000", "10000", List.of("/data", "/data/plugins")));

        assertEquals(
                List.of(
                        "run",
                        "--rm",
                        "--no-deps",
                        "-T",
                        "--pull",
                        "never",
                        "--user",
                        "0:0",
                        "--cap-add",
                        "CHOWN",
                        "--cap-add",
                        "DAC_READ_SEARCH",
                        "--entrypoint",
                        "find",
                        "smp",
                        "/data",
                        "/data/plugins",
                        "(",
                        "!",
                        "-user",
                        "10000",
                        "-o",
                        "!",
                        "-group",
                        "10000",
                        ")",
                        "-exec",
                        "chown",
                        "-h",
                        "10000:10000",
                        "{}",
                        "+"),
                command.subList(command.indexOf("run"), command.size()));
        assertFalse(command.contains("--detach"), "the start must wait for the hand-over: " + command);
    }

    private static JsonObject definition(final String json) {
        return Json.decode(json, JsonObject.class);
    }
}
