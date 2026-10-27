package eu.nordtal.season.messages.document;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One part of a document's tree, as Components V2 draws it.
 *
 * @param accent a container's accent colour as {@code #rrggbb}
 * @param parts what a container holds, a section's text blocks or what a block per language repeats
 * @param button a section's button
 * @param thumbnail a section's image, in place of a button
 * @param media a gallery's images, each {@code attachment://name}, {@code upload://id} or an https URL, or a file's one
 *     address
 * @param items a row's buttons, or its one select or field
 * @param slot the slot of a block per language, a menu's item or a menu's label
 * @param shared the name of the shared part drawn in this part's place, else {@code null}; such a part holds nothing
 *     but its type and its condition
 */
public record Part(
        Type type,
        @Nullable String when,
        @Nullable String accent,
        @Nullable List<Part> parts,
        @Nullable List<Line> lines,
        @Nullable Item button,
        @Nullable String thumbnail,
        @Nullable List<String> media,
        @Nullable List<Item> items,
        @Nullable String slot,
        @Nullable String shared) {

    public enum Type {
        /** A box with an accent colour along its edge, as an embed is drawn. */
        CONTAINER,
        TEXT,
        /** One to three text blocks with a button or a thumbnail beside them. */
        SECTION,
        SEPARATOR,
        /** One image or several side by side. */
        GALLERY,
        /** One file to download, packed with the message: its one {@code media} is no https URL. */
        FILE,
        /** An action row: up to five buttons, or one select or field. */
        ROW,
        /** Repeats the parts it holds once per configured language. */
        LANGUAGES,
        /** A menu's item: its first shown line is the name, the others its lore. */
        ITEM,
        /** A word painted into a menu's window: one plain line. */
        LABEL
    }

    public Part {
        Objects.requireNonNull(type, "type");
        parts = parts == null ? null : List.copyOf(parts);
        lines = lines == null ? null : List.copyOf(lines);
        media = media == null ? null : List.copyOf(media);
        items = items == null ? null : List.copyOf(items);
    }

    public List<Part> inner() {
        return Objects.requireNonNullElse(parts, List.of());
    }

    public List<Line> allLines() {
        return Objects.requireNonNullElse(lines, List.of());
    }

    public List<Item> allItems() {
        return Objects.requireNonNullElse(items, List.of());
    }

    public List<String> allMedia() {
        return Objects.requireNonNullElse(media, List.of());
    }

    public static Part row(final List<Item> items) {
        return new Part(Type.ROW, null, null, null, null, null, null, null, items, null, null);
    }

    public static Part text(final List<Line> lines) {
        return new Part(Type.TEXT, null, null, null, lines, null, null, null, null, null, null);
    }

    /**
     * Returns this part with each shared part it names drawn in its place, under the condition of that place.
     * A name that {@code shared} lacks, or one reached again through {@code through}, draws nothing.
     */
    List<Part> resolved(final Map<String, Part> shared, final Set<String> through) {
        if (this.shared != null) {
            final Part saved = shared.get(this.shared);
            if (saved == null || through.contains(this.shared)) {
                return List.of();
            }
            final Set<String> deeper = new HashSet<>(through);
            deeper.add(this.shared);
            return new Part(
                            saved.type,
                            when == null ? saved.when : when,
                            saved.accent,
                            saved.parts,
                            saved.lines,
                            saved.button,
                            saved.thumbnail,
                            saved.media,
                            saved.items,
                            saved.slot,
                            null)
                    .resolved(shared, deeper);
        }
        if (parts == null) {
            return List.of(this);
        }
        return List.of(new Part(
                type,
                when,
                accent,
                parts.stream()
                        .flatMap(part -> part.resolved(shared, through).stream())
                        .toList(),
                lines,
                button,
                thumbnail,
                media,
                items,
                slot,
                null));
    }

    void collect(final List<Part> into) {
        into.add(this);
        inner().forEach(part -> part.collect(into));
    }
}
