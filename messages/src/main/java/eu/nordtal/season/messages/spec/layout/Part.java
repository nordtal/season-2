package eu.nordtal.season.messages.spec.layout;

import eu.nordtal.season.messages.Tone;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One part of a Discord layout's tree, as Components V2 draws it; its type says which of the other components it has.
 *
 * @param type     what it is
 * @param accent   a container's accent colour as {@code #rrggbb}, or {@code null} for none
 * @param parts    what a container holds, a section's text blocks, or what a block per language repeats
 * @param lines    a text block's lines, drawn one under the other
 * @param element  the element of a block per language, or a section's button
 * @param elements a row's buttons, or its one select
 * @param media    a gallery's images, or a section's one thumbnail, each {@code attachment://name} or an https URL
 */
public record Part(
        Type type,
        @Nullable String accent,
        @Nullable List<Part> parts,
        @Nullable List<Line> lines,
        @Nullable String element,
        @Nullable List<String> elements,
        @Nullable List<String> media) {

    /** The kinds of part. */
    public enum Type {
        /** A box with an accent colour along its edge, as an embed is drawn. */
        CONTAINER,
        /** A text block. */
        TEXT,
        /** One to three text blocks with a button or a thumbnail beside them. */
        SECTION,
        /** A divider line. */
        SEPARATOR,
        /** One image or several side by side. */
        GALLERY,
        /** An action row: up to five buttons, or one select. */
        ROW,
        /** The parts it holds, once per configured language; its element names it. */
        LANGUAGES
    }

    public Part {
        Objects.requireNonNull(type, "type");
        parts = parts == null ? null : List.copyOf(parts);
        lines = lines == null ? null : List.copyOf(lines);
        elements = elements == null ? null : List.copyOf(elements);
        media = media == null ? null : List.copyOf(media);
    }

    public static Part container(final Tone accent, final Part... parts) {
        return new Part(Type.CONTAINER, accent.hex(), List.of(parts), null, null, null, null);
    }

    public static Part text(final Line... lines) {
        return new Part(Type.TEXT, null, null, List.of(lines), null, null, null);
    }

    public static Part sectionWithButton(final String button, final Part... texts) {
        return new Part(Type.SECTION, null, List.of(texts), null, button, null, null);
    }

    public static Part sectionWithThumbnail(final String image, final Part... texts) {
        return new Part(Type.SECTION, null, List.of(texts), null, null, null, List.of(image));
    }

    public static Part separator() {
        return new Part(Type.SEPARATOR, null, null, null, null, null, null);
    }

    public static Part gallery(final String... images) {
        return new Part(Type.GALLERY, null, null, null, null, null, List.of(images));
    }

    public static Part row(final String... elements) {
        return new Part(Type.ROW, null, null, null, null, List.of(elements), null);
    }

    public static Part languages(final String element, final Part... parts) {
        return new Part(Type.LANGUAGES, null, List.of(parts), null, element, null, null);
    }

    /** Adds this part and every part it holds to {@code into}, each before the parts it holds. */
    void collect(final List<Part> into) {
        into.add(this);
        if (parts != null) {
            parts.forEach(part -> part.collect(into));
        }
    }
}
