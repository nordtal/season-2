package eu.nordtal.season.smp;

import io.papermc.paper.datapack.Datapack;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import net.kyori.adventure.text.Component;

/**
 * Brings the map's structure pack in the plugin's jar, above every other pack.
 *
 * Its structure sets then outweigh those of Terralith and DnT. The entrypoint removes every pack it was not given.
 */
public final class SmpBootstrap implements PluginBootstrap {

    /** The folder of the structure pack in the plugin's jar. */
    public static final String STRUCTURES = "/structures";

    /** The id the server lists the structure pack under, after the plugin's name. */
    public static final String STRUCTURES_ID = "structures";

    @Override
    public void bootstrap(final BootstrapContext context) {
        final URI pack = structures();
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY.newHandler(event -> {
            try {
                event.registrar()
                        .discoverPack(
                                context.getPluginMeta(),
                                pack,
                                STRUCTURES_ID,
                                configurer -> configurer
                                        .title(Component.text("Nordtal structures"))
                                        .autoEnableOnServerStart(true)
                                        .position(true, Datapack.Position.TOP));
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }));
    }

    /** Returns where the structure pack lies in the plugin's jar. */
    static URI structures() {
        final URL url = SmpBootstrap.class.getResource(STRUCTURES);
        if (url == null) {
            throw new IllegalStateException("The plugin's jar holds no structure pack.");
        }
        try {
            return url.toURI();
        } catch (final URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
