package eu.nordtal.season.database.document;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.Part;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

final class JdbiDocumentStore implements DocumentStore {

    private final Jdbi jdbi;

    JdbiDocumentStore(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public List<StoredDocument> documents(final Collection<String> namespaces) {
        if (namespaces.isEmpty()) {
            return List.of();
        }
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT namespace, name, document::text AS document, replaced FROM document_override
                        WHERE namespace = ANY(:namespaces) ORDER BY namespace, name""")
                .bindArray("namespaces", String.class, List.copyOf(namespaces))
                .map((rows, context) -> new StoredDocument(
                        rows.getString("namespace"),
                        rows.getString("name"),
                        Document.decode(rows.getString("document")),
                        rows.getString("replaced")))
                .list());
    }

    @Override
    public void change(final StoredDocument document, final Actor actor) {
        jdbi.useTransaction(handle -> {
            write(handle, document, actor);
            signal(handle, document.namespace());
        });
    }

    // Writes an admin's document and keeps it as a version.
    private static void write(final Handle handle, final StoredDocument document, final Actor actor) {
        for (final String address : uploads(document.document()).toList()) {
            final long id = Image.idOf(address).orElseThrow();
            if (handle.createQuery("SELECT count(*) FROM layout_image WHERE id = :id")
                            .bind("id", id)
                            .mapTo(Integer.class)
                            .one()
                    == 0) {
                throw new IllegalArgumentException("no image is stored as " + address);
            }
        }
        handle.createUpdate("""
                        INSERT INTO document_override (namespace, name, document, replaced, actor_kind, actor_id, changed)
                        VALUES (:namespace, :name, CAST(:document AS jsonb), :replaced, :kind, :id, now())
                        ON CONFLICT (namespace, name) DO UPDATE SET document = excluded.document,
                            replaced = excluded.replaced, actor_kind = excluded.actor_kind,
                            actor_id = excluded.actor_id, changed = excluded.changed""")
                .bind("namespace", document.namespace())
                .bind("name", document.name())
                .bind("document", document.document().json())
                .bind("replaced", document.replaced())
                .bind("kind", actor.kind().name())
                .bind("id", actor.id())
                .execute();
        keep(handle, document.namespace(), document.name(), document, actor);
    }

    @Override
    public List<SharedPart> sharedParts() {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT name, part::text AS part FROM shared_part ORDER BY name")
                .map((rows, context) ->
                        new SharedPart(rows.getString("name"), Json.decode(rows.getString("part"), Part.class)))
                .list());
    }

    @Override
    public void changeSharedPart(
            final String name, final @Nullable Part part, final List<StoredDocument> rewritten, final Actor actor) {
        jdbi.useTransaction(handle -> {
            if (part == null) {
                handle.createUpdate("DELETE FROM shared_part WHERE name = :name")
                        .bind("name", name)
                        .execute();
            } else {
                handle.createUpdate("""
                                INSERT INTO shared_part (name, part, actor_kind, actor_id, changed)
                                VALUES (:name, CAST(:part AS jsonb), :kind, :id, now())
                                ON CONFLICT (name) DO UPDATE SET part = excluded.part, actor_kind = excluded.actor_kind,
                                    actor_id = excluded.actor_id, changed = excluded.changed""")
                        .bind("name", name)
                        .bind("part", Json.encode(part))
                        .bind("kind", actor.kind().name())
                        .bind("id", actor.id())
                        .execute();
            }
            rewritten.forEach(document -> write(handle, document, actor));
            rewritten.stream()
                    .map(StoredDocument::namespace)
                    .distinct()
                    .forEach(namespace -> signal(handle, namespace));
        });
    }

    // Keeps a save as a version in the transaction that wrote it; a removal keeps no document.
    private static void keep(
            final Handle handle,
            final String namespace,
            final String name,
            final @Nullable StoredDocument document,
            final Actor actor) {
        handle.createUpdate("""
                        INSERT INTO document_version (namespace, name, document, replaced, actor_kind, actor_id, saved)
                        VALUES (:namespace, :name, CAST(:document AS jsonb), :replaced, :kind, :id, now())""")
                .bind("namespace", namespace)
                .bind("name", name)
                .bind("document", document == null ? null : document.document().json())
                .bind("replaced", document == null ? null : document.replaced())
                .bind("kind", actor.kind().name())
                .bind("id", actor.id())
                .execute();
    }

    @Override
    public List<Version> versions(final String namespace, final String name) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT id, document::text AS document, replaced, actor_kind, actor_id, saved FROM document_version
                        WHERE namespace = :namespace AND name = :name ORDER BY id DESC""")
                .bind("namespace", namespace)
                .bind("name", name)
                .map((rows, context) -> {
                    final @Nullable String document = rows.getString("document");
                    return new Version(
                            rows.getLong("id"),
                            document == null
                                    ? null
                                    : new StoredDocument(
                                            namespace, name, Document.decode(document), rows.getString("replaced")),
                            Actor.of(rows.getString("actor_kind"), rows.getString("actor_id")),
                            rows.getObject("saved", OffsetDateTime.class).toInstant());
                })
                .list());
    }

    private static Stream<String> uploads(final Document document) {
        return document.allParts().stream()
                .flatMap(part -> Stream.concat(part.allMedia().stream(), Stream.ofNullable(part.thumbnail())))
                .filter(address -> address.startsWith(Image.SCHEME));
    }

    @Override
    public void remove(final String namespace, final String name, final Actor actor) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("DELETE FROM document_override WHERE namespace = :namespace AND name = :name")
                    .bind("namespace", namespace)
                    .bind("name", name)
                    .execute();
            keep(handle, namespace, name, null, actor);
            signal(handle, namespace);
        });
    }

    // Sends the signal in the transaction that wrote the row.
    private static void signal(final Handle handle, final String namespace) {
        handle.createQuery("SELECT pg_notify('nordtal_messages', :namespace) IS NULL")
                .bind("namespace", namespace)
                .mapTo(Boolean.class)
                .one();
    }

    @Override
    public long upload(final String mediaType, final byte[] bytes, final Actor actor) {
        return jdbi.inTransaction(handle -> {
            handle.createUpdate("""
                            INSERT INTO layout_image (media_type, bytes, actor_kind, actor_id, uploaded)
                            VALUES (:type, :bytes, :kind, :id, now())
                            ON CONFLICT (sha256) DO NOTHING""")
                    .bind("type", mediaType)
                    .bind("bytes", bytes)
                    .bind("kind", actor.kind().name())
                    .bind("id", actor.id())
                    .execute();
            return handle.createQuery("SELECT id FROM layout_image WHERE sha256 = encode(sha256(:bytes), 'hex')")
                    .bind("bytes", bytes)
                    .mapTo(Long.class)
                    .one();
        });
    }

    @Override
    public Optional<Image> image(final long id) {
        return jdbi.withHandle(
                handle -> handle.createQuery("SELECT id, media_type, bytes FROM layout_image WHERE id = :id")
                        .bind("id", id)
                        .map((rows, context) ->
                                new Image(rows.getLong("id"), rows.getString("media_type"), rows.getBytes("bytes")))
                        .findOne());
    }
}
