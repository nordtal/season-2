package eu.nordtal.season.database.message;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.messages.MessageOverride;
import eu.nordtal.season.messages.PackagedTexts;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

final class JdbiMessageOverrideStore implements MessageOverrideStore {

    private final Jdbi jdbi;

    JdbiMessageOverrideStore(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public List<MessageOverride> overrides(final Collection<String> bundles) {
        if (bundles.isEmpty()) {
            return List.of();
        }
        final Map<String, List<String>> originals = originals(bundles);
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT bundle, key, language, variant, text FROM message_override
                        WHERE bundle = ANY(:bundles) ORDER BY bundle, key, language, variant""")
                .bindArray("bundles", String.class, List.copyOf(bundles))
                .map((rows, context) -> {
                    final String bundle = rows.getString("bundle");
                    final String key = rows.getString("key");
                    final String language = rows.getString("language");
                    final @Nullable List<String> original = originals.get(bundle + "/" + key + "/" + language);
                    return new MessageOverride(
                            bundle,
                            key,
                            language,
                            rows.getInt("variant"),
                            rows.getString("text"),
                            original == null ? null : PackagedTexts.hash(original));
                })
                .list());
    }

    @Override
    public Map<String, List<String>> originals(final Collection<String> bundles) {
        final Map<String, List<String>> originals = new LinkedHashMap<>();
        if (bundles.isEmpty()) {
            return originals;
        }
        jdbi.useHandle(handle -> handle.createQuery("""
                        SELECT bundle, key, language, text FROM message_override_original
                        WHERE bundle = ANY(:bundles) ORDER BY bundle, key, language, variant""")
                .bindArray("bundles", String.class, List.copyOf(bundles))
                .map((rows, context) -> Map.entry(
                        rows.getString("bundle") + "/" + rows.getString("key") + "/" + rows.getString("language"),
                        rows.getString("text")))
                .forEach(original -> originals
                        .computeIfAbsent(original.getKey(), key -> new ArrayList<>())
                        .add(original.getValue())));
        return originals;
    }

    @Override
    public void change(
            final String bundle,
            final String key,
            final String language,
            final List<String> texts,
            final List<String> original,
            final Actor actor) {
        final @Nullable String replaced = original.isEmpty() ? null : PackagedTexts.hash(original);
        jdbi.useTransaction(handle -> {
            for (final String table : List.of("message_override", "message_override_original")) {
                handle.createUpdate("DELETE FROM " + table
                                + " WHERE bundle = :bundle AND key = :key AND language = :language")
                        .bind("bundle", bundle)
                        .bind("key", key)
                        .bind("language", language)
                        .execute();
            }
            for (int variant = 0; variant < texts.size(); variant++) {
                handle.createUpdate("""
                                INSERT INTO message_override
                                    (bundle, key, language, variant, text, replaced, actor_kind, actor_id, changed)
                                VALUES (:bundle, :key, :language, :variant, :text, :replaced, :kind, :id, now())""")
                        .bind("bundle", bundle)
                        .bind("key", key)
                        .bind("language", language)
                        .bind("variant", variant)
                        .bind("text", texts.get(variant))
                        .bind("replaced", replaced)
                        .bind("kind", actor.kind().name())
                        .bind("id", actor.id())
                        .execute();
            }
            for (int variant = 0; !texts.isEmpty() && variant < original.size(); variant++) {
                handle.createUpdate("""
                                INSERT INTO message_override_original (bundle, key, language, variant, text)
                                VALUES (:bundle, :key, :language, :variant, :text)""")
                        .bind("bundle", bundle)
                        .bind("key", key)
                        .bind("language", language)
                        .bind("variant", variant)
                        .bind("text", original.get(variant))
                        .execute();
            }
            // In the same transaction, so no reader sees the signal before the rows.
            handle.createQuery("SELECT pg_notify('nordtal_messages', :bundle) IS NULL")
                    .bind("bundle", bundle)
                    .mapTo(Boolean.class)
                    .one();
        });
    }
}
