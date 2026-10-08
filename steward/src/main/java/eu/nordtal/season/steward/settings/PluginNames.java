package eu.nordtal.season.steward.settings;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.message.MessageOverrideStore;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.MessageEntry;
import eu.nordtal.season.messages.MessageOverride;
import eu.nordtal.season.messages.text.Filling;
import eu.nordtal.season.messages.text.MessageSyntaxException;
import eu.nordtal.season.messages.text.MessageText;
import eu.nordtal.season.messages.text.Piece;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The plain English names a service's texts give its choices' values, an admin's override before the jar's text.
 *
 * The jars are read on the first name asked; where steward-agent cannot answer, every value goes unnamed.
 */
final class PluginNames implements SettingsDocument.Names {

    private static final Logger LOG = LoggerFactory.getLogger(PluginNames.class);

    private final AgentClient agent;
    private final @Nullable MessageOverrideStore overrides;
    private final String service;
    private @Nullable Map<String, String> byKey;

    private PluginNames(final AgentClient agent, final @Nullable MessageOverrideStore overrides, final String service) {
        this.agent = agent;
        this.overrides = overrides;
        this.service = service;
    }

    /** The names of {@code service}'s jars, read through {@code agent} with the overrides of {@code overrides}. */
    static SettingsDocument.Names of(
            final AgentClient agent, final @Nullable MessageOverrideStore overrides, final String service) {
        return new PluginNames(agent, overrides, service);
    }

    @Override
    public @Nullable String name(final String key) {
        Map<String, String> read = byKey;
        if (read == null) {
            read = read();
            byKey = read;
        }
        return read.get(key);
    }

    private Map<String, String> read() {
        final Map<String, MessageEntry> entries = new HashMap<>();
        try {
            for (final AgentWire.BundleRef location : agent.bundles()) {
                if (location.service().equals(service)) {
                    agent.bundle(location.service(), location.module())
                            .entries()
                            .forEach(entry -> entries.putIfAbsent(entry.key(), entry));
                }
            }
        } catch (final RuntimeException unreachable) {
            LOG.warn("the texts of {} could not be read, so its choices show their values", service, unreachable);
            return Map.of();
        }
        final Map<String, String> overridden = overridden(entries);
        final Map<String, String> names = new HashMap<>();
        entries.forEach((key, entry) -> {
            final String source = overridden.getOrDefault(
                    entry.bundle() + "/" + key,
                    entry.packaged(Locales.DEFAULT_TAG).stream().findFirst().orElse(""));
            if (!source.isBlank()) {
                names.put(key, plain(source, !"PLAIN".equals(entry.format())));
            }
        });
        return names;
    }

    /** The English override of each key, by bundle and key, its first variant. */
    private Map<String, String> overridden(final Map<String, MessageEntry> entries) {
        final MessageOverrideStore store = overrides;
        if (store == null || entries.isEmpty()) {
            return Map.of();
        }
        final Set<String> bundles =
                entries.values().stream().map(MessageEntry::bundle).collect(Collectors.toSet());
        return store.overrides(bundles).stream()
                .filter(row -> row.language().equals(Locales.DEFAULT_TAG) && row.variant() == 0)
                .collect(Collectors.toMap(row -> row.bundle() + "/" + row.key(), MessageOverride::text));
    }

    /** A name's words without its markup; one that cannot be read is shown as written. */
    private static String plain(final String source, final boolean markup) {
        try {
            final List<Piece> pieces = Filling.fill(MessageText.parse(source, markup), Map.of(), Map.of(), name -> {});
            return pieces.stream()
                    .filter(Piece.Text.class::isInstance)
                    .map(piece -> ((Piece.Text) piece).text())
                    .collect(Collectors.joining())
                    .strip();
        } catch (final MessageSyntaxException unreadable) {
            return source;
        }
    }
}
