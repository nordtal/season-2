package eu.nordtal.season.bridgemod;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ModDescriptorTest {

    @Test
    void theModAcceptsTheLoaderFabricNamesForTheSeasonsMinecraft() throws IOException {
        // Fabric names 0.19.5 for players of 26.3. A compile bump of the loader must not raise it.
        final String descriptor;
        try (InputStream in = BridgeMod.class.getResourceAsStream("/fabric.mod.json")) {
            assertNotNull(in, "fabric.mod.json is on the classpath");
            descriptor = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(
                descriptor.contains("\"fabricloader\": \">=0.19.5\""),
                "fabric.mod.json requires Fabric Loader 0.19.5 or later:\n" + descriptor);
    }
}
