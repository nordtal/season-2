package eu.nordtal.season.bridgemod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Starts the bridge when the instance's config folder holds the Nordtal Designer's settings file. */
public final class BridgeMod implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("nordtal-bridge");

    @Override
    public void onInitializeClient() {
        final Path file = FabricLoader.getInstance().getConfigDir().resolve(BridgeSettings.FILE);
        if (!Files.exists(file)) {
            LOG.info("No {}, so the bridge stays off.", file);
            return;
        }
        try {
            final BridgeSettings settings = BridgeSettings.read(file);
            // A daemon event loop ends with the client, so nothing has to close the server.
            final BridgeServer server = new BridgeServer(
                    settings.port(),
                    Duration.ofSeconds(5),
                    token -> BridgeSettings.accepts(file, token),
                    new ClientActions(Minecraft.getInstance()));
            LOG.info("The bridge listens on 127.0.0.1:{}.", server.port());
        } catch (Exception e) {
            LOG.error("The bridge stays off: {}", e.getMessage(), e);
        }
    }
}
