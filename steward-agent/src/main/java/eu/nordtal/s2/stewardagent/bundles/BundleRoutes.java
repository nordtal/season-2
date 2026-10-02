package eu.nordtal.s2.stewardagent.bundles;

import eu.nordtal.s2.internalapi.InternalServer;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.MessageArg;
import eu.nordtal.s2.internalapi.agent.MessageBundle;
import eu.nordtal.s2.internalapi.agent.MessageEntry;
import io.javalin.config.JavalinConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.NotFoundResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The message bundles the services' jars carry, merged with the override files beside them, read and saved.
 *
 * A save is checked against the packaged placeholders first, so a refused one writes nothing.
 */
public final class BundleRoutes {

    private static final Set<String> LANGUAGES = Set.of("en", "de");

    private final Path configs;
    private final ImageJars images;

    /**
     * @param configs one directory per service, holding its plugins' data folders
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
                        .map(location ->
                                new AgentWire.BundleRef(location.service(), location.module(), location.writable()))
                        .toList()));
        config.routes.get(AgentWire.BUNDLE, ctx -> ctx.json(read(locate(ctx))));
        config.routes.post(AgentWire.BUNDLE, this::save);
    }

    private void save(final Context ctx) {
        final MessageBundleLocation location = locate(ctx);
        if (!location.writable()) {
            throw new ForbiddenResponse(name(location) + " is mounted read-only, so a change to it cannot be saved.");
        }
        final AgentWire.BundleChanges body = InternalServer.body(ctx, AgentWire.BundleChanges.class);
        final Map<String, Map<String, String>> byLanguage = byLanguage(body);
        final MessageBundle before = read(location);
        final List<String> problems = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        byLanguage.forEach((language, changes) -> {
            problems.addAll(unknownPlaceholders(before, changes));
            warnings.addAll(droppedPlaceholders(before, language, changes));
        });
        if (!problems.isEmpty()) {
            throw new BadRequestResponse(String.join(" ", problems) + " Nothing was saved.");
        }
        try {
            for (final Map.Entry<String, Map<String, String>> language : byLanguage.entrySet()) {
                MessageBundles.write(location, language.getKey(), language.getValue());
            }
        } catch (final IOException e) {
            throw new InternalServerErrorResponse(name(location) + " could not be written: " + e.getMessage());
        }
        ctx.json(new AgentWire.SavedBundle(read(location), warnings));
    }

    /** The changes split by language; an unknown language or an empty list is refused. */
    private static Map<String, Map<String, String>> byLanguage(final AgentWire.@Nullable BundleChanges body) {
        // Gson leaves a missing field null whatever the record declares.
        if (body == null || body.changes() == null || body.changes().isEmpty()) {
            throw new BadRequestResponse("there is nothing to save");
        }
        final Map<String, Map<String, String>> byLanguage = new LinkedHashMap<>();
        for (final AgentWire.TextChange change : body.changes()) {
            if (change.key() == null || !LANGUAGES.contains(change.language())) {
                throw new BadRequestResponse("a change names a key and the language en or de");
            }
            byLanguage
                    .computeIfAbsent(change.language(), language -> new LinkedHashMap<>())
                    .put(change.key(), change.text());
        }
        return byLanguage;
    }

    private static List<String> unknownPlaceholders(final MessageBundle before, final Map<String, String> changes) {
        final List<String> problems = new ArrayList<>();
        changes.forEach((key, text) -> entry(before, key).ifPresent(entry -> {
            final List<String> unknown = MessageBundles.unknownPlaceholders(entry, text);
            if (!unknown.isEmpty()) {
                problems.add(key + " has no placeholder " + String.join(", ", unknown)
                        + (entry.args().isEmpty()
                                ? "; it takes none."
                                : "; it takes "
                                        + String.join(
                                                ", ",
                                                entry.args().stream()
                                                        .map(MessageArg::token)
                                                        .toList()) + "."));
            }
        }));
        return problems;
    }

    /** A warning for every changed key whose new text lost a placeholder the packaged text had. */
    private static List<String> droppedPlaceholders(
            final MessageBundle before, final String language, final Map<String, String> changes) {
        final List<String> warnings = new ArrayList<>();
        changes.forEach((key, edited) -> {
            if (edited == null) {
                // A reset has no new text to check placeholders against.
                return;
            }
            entry(before, key).ifPresent(entry -> {
                final String original =
                        "de".equals(language) && entry.german() != null ? entry.german() : entry.english();
                final List<String> missing = MessageBundles.missingPlaceholders(original, edited);
                if (!missing.isEmpty()) {
                    warnings.add(key + " no longer contains " + String.join(", ", missing)
                            + " - the original had it, and a message this is substituted into may now"
                            + " draw literally.");
                }
            });
        });
        return warnings;
    }

    private static Optional<MessageEntry> entry(final MessageBundle bundle, final String key) {
        return bundle.entries().stream()
                .filter(candidate -> candidate.key().equals(key))
                .findFirst();
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
        } catch (final IOException e) {
            throw new InternalServerErrorResponse(name(location) + " could not be read: " + e.getMessage());
        }
    }

    private static String name(final MessageBundleLocation location) {
        return location.module().isEmpty() ? location.service() : location.service() + "/" + location.module();
    }
}
