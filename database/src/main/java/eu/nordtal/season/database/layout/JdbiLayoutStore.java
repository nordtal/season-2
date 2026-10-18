package eu.nordtal.season.database.layout;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.messages.spec.layout.Element;
import eu.nordtal.season.messages.spec.layout.Line;
import eu.nordtal.season.messages.spec.layout.Part;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

final class JdbiLayoutStore implements LayoutStore {

    private final Jdbi jdbi;

    JdbiLayoutStore(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
    }

    /** One {@code layout_part} row with the rows of its other tables. */
    private static final class Row {
        final Part.Type type;
        final @Nullable String accent;
        final @Nullable String element;
        final List<Integer> children = new ArrayList<>();
        final List<Line> lines = new ArrayList<>();
        final List<String> elements = new ArrayList<>();
        final List<String> media = new ArrayList<>();

        Row(final Part.Type type, final @Nullable String accent, final @Nullable String element) {
            this.type = type;
            this.accent = accent;
            this.element = element;
        }

        Part part(final Map<Integer, Row> rows) {
            final boolean holds =
                    type == Part.Type.CONTAINER || type == Part.Type.SECTION || type == Part.Type.LANGUAGES;
            return new Part(
                    type,
                    accent,
                    holds
                            ? children.stream()
                                    .map(id ->
                                            Objects.requireNonNull(rows.get(id)).part(rows))
                                    .toList()
                            : null,
                    type == Part.Type.TEXT ? lines : null,
                    element,
                    type == Part.Type.ROW ? elements : null,
                    type == Part.Type.GALLERY || !media.isEmpty() ? media : null);
        }
    }

    @Override
    public List<StoredLayout> layouts(final Collection<String> bundles) {
        if (bundles.isEmpty()) {
            return List.of();
        }
        return jdbi.withHandle(handle -> {
            final List<String> asked = List.copyOf(bundles);
            final Map<String, String> replaced = new LinkedHashMap<>();
            handle.createQuery("SELECT bundle, layout, replaced FROM layout_override WHERE bundle = ANY(:bundles)"
                            + " ORDER BY bundle, layout")
                    .bindArray("bundles", String.class, asked)
                    .map((rows, context) -> Map.entry(
                            rows.getString("bundle") + "/" + rows.getString("layout"), rows.getString("replaced")))
                    .forEach(row -> replaced.put(row.getKey(), row.getValue()));
            final Map<String, Map<Integer, Row>> parts = parts(handle, asked);
            final Map<String, List<Element>> links = links(handle, asked);
            final List<StoredLayout> layouts = new ArrayList<>();
            replaced.forEach((id, hash) -> {
                final Map<Integer, Row> rows = parts.getOrDefault(id, Map.of());
                final List<Part> top = rows.entrySet().stream()
                        .filter(row -> rows.values().stream().noneMatch(other -> other.children.contains(row.getKey())))
                        .map(row -> row.getValue().part(rows))
                        .toList();
                final String[] name = id.split("/", 2);
                layouts.add(new StoredLayout(name[0], name[1], top, links.getOrDefault(id, List.of()), hash));
            });
            return layouts;
        });
    }

    private static Map<String, Map<Integer, Row>> parts(final Handle handle, final List<String> bundles) {
        final Map<String, Map<Integer, Row>> parts = new HashMap<>();
        handle.createQuery("SELECT bundle, layout, part, parent, type, accent, element FROM layout_part"
                        + " WHERE bundle = ANY(:bundles) ORDER BY bundle, layout, part")
                .bindArray("bundles", String.class, bundles)
                .map((rows, context) -> {
                    final Map<Integer, Row> layout = parts.computeIfAbsent(
                            rows.getString("bundle") + "/" + rows.getString("layout"), ignored -> new TreeMap<>());
                    final int part = rows.getInt("part");
                    layout.put(
                            part,
                            new Row(
                                    Part.Type.valueOf(rows.getString("type")),
                                    rows.getString("accent"),
                                    rows.getString("element")));
                    final int parent = rows.getInt("parent");
                    if (!rows.wasNull()) {
                        Objects.requireNonNull(layout.get(parent)).children.add(part);
                    }
                    return part;
                })
                .list();
        lines(handle, bundles, parts);
        placed(handle, bundles, parts);
        return parts;
    }

    private static void lines(
            final Handle handle, final List<String> bundles, final Map<String, Map<Integer, Row>> parts) {
        final Map<String, Map<String, String>> texts = new HashMap<>();
        handle.createQuery("SELECT bundle, layout, part, line, language, text FROM layout_line_text"
                        + " WHERE bundle = ANY(:bundles)")
                .bindArray("bundles", String.class, bundles)
                .map((rows, context) -> texts.computeIfAbsent(
                                rows.getString("bundle") + "/" + rows.getString("layout") + "/" + rows.getInt("part")
                                        + "/" + rows.getInt("line"),
                                ignored -> new TreeMap<>())
                        .put(rows.getString("language"), rows.getString("text")))
                .list();
        handle.createQuery("SELECT bundle, layout, part, line, key, list, style FROM layout_line"
                        + " WHERE bundle = ANY(:bundles) ORDER BY bundle, layout, part, line")
                .bindArray("bundles", String.class, bundles)
                .map((rows, context) -> {
                    final String id = rows.getString("bundle") + "/" + rows.getString("layout");
                    final String style = rows.getString("style");
                    final Line line = new Line(
                            rows.getString("key"),
                            rows.getString("list"),
                            style == null ? null : Line.Style.valueOf(style),
                            texts.get(id + "/" + rows.getInt("part") + "/" + rows.getInt("line")));
                    row(parts, id, rows.getInt("part")).lines.add(line);
                    return line;
                })
                .list();
    }

    private static void placed(
            final Handle handle, final List<String> bundles, final Map<String, Map<Integer, Row>> parts) {
        handle.createQuery("SELECT bundle, layout, part, element FROM layout_row_element"
                        + " WHERE bundle = ANY(:bundles) ORDER BY bundle, layout, part, position")
                .bindArray("bundles", String.class, bundles)
                .map((rows, context) -> row(
                                parts, rows.getString("bundle") + "/" + rows.getString("layout"), rows.getInt("part"))
                        .elements
                        .add(rows.getString("element")))
                .list();
        handle.createQuery("SELECT bundle, layout, part, address, image FROM layout_media"
                        + " WHERE bundle = ANY(:bundles) ORDER BY bundle, layout, part, position")
                .bindArray("bundles", String.class, bundles)
                .map((rows, context) -> {
                    final String address = rows.getString("address");
                    return row(parts, rows.getString("bundle") + "/" + rows.getString("layout"), rows.getInt("part"))
                            .media
                            .add(address != null ? address : LayoutImage.SCHEME + rows.getLong("image"));
                })
                .list();
    }

    private static Row row(final Map<String, Map<Integer, Row>> parts, final String id, final int part) {
        return Objects.requireNonNull(Objects.requireNonNull(parts.get(id), id).get(part), id);
    }

    private static Map<String, List<Element>> links(final Handle handle, final List<String> bundles) {
        final Map<String, Map<String, String>> labels = new HashMap<>();
        handle.createQuery("SELECT bundle, layout, element, language, label FROM layout_link_label"
                        + " WHERE bundle = ANY(:bundles)")
                .bindArray("bundles", String.class, bundles)
                .map((rows, context) -> labels.computeIfAbsent(
                                rows.getString("bundle") + "/" + rows.getString("layout") + "/"
                                        + rows.getString("element"),
                                ignored -> new TreeMap<>())
                        .put(rows.getString("language"), rows.getString("label")))
                .list();
        final Map<String, List<Element>> links = new HashMap<>();
        handle.createQuery("SELECT bundle, layout, element, url FROM layout_link"
                        + " WHERE bundle = ANY(:bundles) ORDER BY bundle, layout, element")
                .bindArray("bundles", String.class, bundles)
                .map((rows, context) -> {
                    final String id = rows.getString("bundle") + "/" + rows.getString("layout");
                    final String element = rows.getString("element");
                    return links.computeIfAbsent(id, ignored -> new ArrayList<>())
                            .add(Element.link(
                                    element, rows.getString("url"), labels.getOrDefault(id + "/" + element, Map.of())));
                })
                .list();
        return links;
    }

    @Override
    public void change(final StoredLayout layout, final Actor actor) {
        jdbi.useTransaction(handle -> {
            delete(handle, layout.bundle(), layout.layout());
            handle.createUpdate("""
                            INSERT INTO layout_override (bundle, layout, replaced, actor_kind, actor_id, changed)
                            VALUES (:bundle, :layout, :replaced, :kind, :id, now())""")
                    .bind("bundle", layout.bundle())
                    .bind("layout", layout.layout())
                    .bind("replaced", layout.replaced())
                    .bind("kind", actor.kind().name())
                    .bind("id", actor.id())
                    .execute();
            final Writer writer = new Writer(handle, layout.bundle(), layout.layout());
            layout.parts().forEach(part -> writer.part(part, null));
            for (final Element link : layout.links()) {
                writer.update("INSERT INTO layout_link (bundle, layout, element, url)"
                                + " VALUES (:bundle, :layout, :element, :url)")
                        .bind("element", link.id())
                        .bind("url", link.url())
                        .execute();
                for (final Map.Entry<String, String> label : Objects.requireNonNullElse(
                                link.labels(), Map.<String, String>of())
                        .entrySet()) {
                    writer.update("INSERT INTO layout_link_label (bundle, layout, element, language, label)"
                                    + " VALUES (:bundle, :layout, :element, :language, :label)")
                            .bind("element", link.id())
                            .bind("language", label.getKey())
                            .bind("label", label.getValue())
                            .execute();
                }
            }
            signal(handle, layout.bundle());
        });
    }

    /** Writes the parts of a tree in read-back order, each before its children. */
    private static final class Writer {
        private final Handle handle;
        private final String bundle;
        private final String layout;
        private int next;

        Writer(final Handle handle, final String bundle, final String layout) {
            this.handle = handle;
            this.bundle = bundle;
            this.layout = layout;
        }

        org.jdbi.v3.core.statement.Update update(final String sql) {
            return handle.createUpdate(sql).bind("bundle", bundle).bind("layout", layout);
        }

        void part(final Part part, final @Nullable Integer parent) {
            final int id = next++;
            update("INSERT INTO layout_part (bundle, layout, part, parent, type, accent, element)"
                            + " VALUES (:bundle, :layout, :part, :parent, :type, :accent, :element)")
                    .bind("part", id)
                    .bind("parent", parent)
                    .bind("type", part.type().name())
                    .bind("accent", part.accent())
                    .bind("element", part.element())
                    .execute();
            final List<Line> lines = Objects.requireNonNullElse(part.lines(), List.of());
            for (int number = 0; number < lines.size(); number++) {
                line(id, number, lines.get(number));
            }
            final List<String> elements = Objects.requireNonNullElse(part.elements(), List.of());
            for (int position = 0; position < elements.size(); position++) {
                update("INSERT INTO layout_row_element (bundle, layout, part, position, element)"
                                + " VALUES (:bundle, :layout, :part, :position, :element)")
                        .bind("part", id)
                        .bind("position", position)
                        .bind("element", elements.get(position))
                        .execute();
            }
            final List<String> media = Objects.requireNonNullElse(part.media(), List.of());
            for (int position = 0; position < media.size(); position++) {
                media(id, position, media.get(position));
            }
            Objects.requireNonNullElse(part.parts(), List.<Part>of()).forEach(inner -> part(inner, id));
        }

        private void line(final int part, final int number, final Line line) {
            update("INSERT INTO layout_line (bundle, layout, part, line, key, list, style)"
                            + " VALUES (:bundle, :layout, :part, :line, :key, :list, :style)")
                    .bind("part", part)
                    .bind("line", number)
                    .bind("key", line.key())
                    .bind("list", line.list())
                    .bind("style", line.style() == null ? null : line.style().name())
                    .execute();
            for (final Map.Entry<String, String> text : Objects.requireNonNullElse(
                            line.texts(), Map.<String, String>of())
                    .entrySet()) {
                update("INSERT INTO layout_line_text (bundle, layout, part, line, language, text)"
                                + " VALUES (:bundle, :layout, :part, :line, :language, :text)")
                        .bind("part", part)
                        .bind("line", number)
                        .bind("language", text.getKey())
                        .bind("text", text.getValue())
                        .execute();
            }
        }

        private void media(final int part, final int position, final String address) {
            final Optional<Long> image = LayoutImage.idOf(address);
            if (image.isPresent()
                    && handle.createQuery("SELECT count(*) FROM layout_image WHERE id = :id")
                                    .bind("id", image.get())
                                    .mapTo(Integer.class)
                                    .one()
                            == 0) {
                throw new IllegalArgumentException("no image is stored as " + address);
            }
            update("INSERT INTO layout_media (bundle, layout, part, position, address, image)"
                            + " VALUES (:bundle, :layout, :part, :position, :address, :image)")
                    .bind("part", part)
                    .bind("position", position)
                    .bind("address", image.isPresent() ? null : address)
                    .bind("image", image.orElse(null))
                    .execute();
        }
    }

    @Override
    public void remove(final String bundle, final String layout) {
        jdbi.useTransaction(handle -> {
            delete(handle, bundle, layout);
            signal(handle, bundle);
        });
    }

    /** Deletes the rebuild. The other tables follow {@code layout_override} by cascade. */
    private static void delete(final Handle handle, final String bundle, final String layout) {
        handle.createUpdate("DELETE FROM layout_override WHERE bundle = :bundle AND layout = :layout")
                .bind("bundle", bundle)
                .bind("layout", layout)
                .execute();
    }

    /** Sends the signal in the transaction that wrote the rows. */
    private static void signal(final Handle handle, final String bundle) {
        handle.createQuery("SELECT pg_notify('nordtal_messages', :bundle) IS NULL")
                .bind("bundle", bundle)
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
    public Optional<LayoutImage> image(final long id) {
        return jdbi.withHandle(
                handle -> handle.createQuery("SELECT id, media_type, bytes FROM layout_image WHERE id = :id")
                        .bind("id", id)
                        .map((rows, context) -> new LayoutImage(
                                rows.getLong("id"), rows.getString("media_type"), rows.getBytes("bytes")))
                        .findOne());
    }
}
