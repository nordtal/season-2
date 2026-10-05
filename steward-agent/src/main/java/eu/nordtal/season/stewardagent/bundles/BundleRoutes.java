package eu.nordtal.season.stewardagent.bundles;

import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.MessageBundle;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.NotFoundResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** The message bundles the services' jars carry, read for the editor; steward keeps the overrides in the database. */
public final class BundleRoutes {

    private final Path configs;
    private final ImageJars images;

    /**
     * @param configs one directory per service, holding its plugins' jars
     * @param images where the bot's jar is found, which is in its image and not beside its data
     */
    public BundleRoutes(final Path configs, final ImageJars images) {
        this.configs = configs;
        this.images = images;
    }

    public void register(final JavalinConfig config) {
        config.routes.get(
                AgentWire.BUNDLES,
                ctx -> ctx.json(MessageBundles.discover(configs, images).stream()
                        .map(location -> new AgentWire.BundleRef(location.service(), location.module()))
                        .toList()));
        config.routes.get(AgentWire.BUNDLE, ctx -> ctx.json(read(locate(ctx))));
    }

    private MessageBundleLocation locate(final Context ctx) {
        final String service = ctx.pathParam("service");
        final String module = Objects.requireNonNullElse(ctx.queryParam("module"), "");
        return MessageBundles.discover(configs, images).stream()
                .filter(location ->
                        location.service().equals(service) && location.module().equals(module))
                .findFirst()
                .orElseThrow(() -> new NotFoundResponse(
                        "There is no message bundle called " + service + (module.isEmpty() ? "" : "/" + module) + "."));
    }

    private static MessageBundle read(final MessageBundleLocation location) {
        try {
            return MessageBundles.read(location);
        } catch (final IOException | IllegalStateException e) {
            throw new InternalServerErrorResponse(name(location) + " could not be read: " + e.getMessage());
        }
    }

    private static String name(final MessageBundleLocation location) {
        return location.module().isEmpty() ? location.service() : location.service() + "/" + location.module();
    }
}
