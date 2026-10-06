package eu.nordtal.season.stewardagent.bundles;

import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.MessageBundle;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.NotFoundResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** The message bundles the services' jars carry, read for the editor; steward keeps the overrides in the database. */
public final class BundleRoutes {

    private final Supplier<List<ServiceJar>> jars;

    /** @param jars every jar of ours on every service, found the way the descriptors are */
    public BundleRoutes(final Supplier<List<ServiceJar>> jars) {
        this.jars = jars;
    }

    public void register(final JavalinConfig config) {
        config.routes.get(
                AgentWire.BUNDLES,
                ctx -> ctx.json(MessageBundles.discover(jars.get()).stream()
                        .map(location -> new AgentWire.BundleRef(location.service(), location.module()))
                        .toList()));
        config.routes.get(AgentWire.BUNDLE, ctx -> ctx.json(read(locate(ctx))));
    }

    private MessageBundleLocation locate(final Context ctx) {
        final String service = ctx.pathParam("service");
        final String module = Objects.requireNonNullElse(ctx.queryParam("module"), "");
        return MessageBundles.discover(jars.get()).stream()
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
