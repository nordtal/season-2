package eu.nordtal.season.messages.spec.layout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Carries an editor's rebuild over a release that changed the packaged layout it was written over.
 * The rebuild keeps its order and its added texts; an element or key the release removed goes, an element it added is
 * placed at the end. A rebuild that still does not fit the release is set aside for the packaged layout.
 */
public final class LayoutMerge {

    private static final int ROW_BUTTONS = 5;

    private LayoutMerge() {}

    /** What the merge changed of a rebuild. */
    public enum Change {
        /** An element the release added, placed at the end; its subject is the element's id. */
        ELEMENT_ADDED,
        /** An element the release removed or gave another kind, taken out; its subject is the element's id. */
        ELEMENT_REMOVED,
        /** A key the release no longer places, taken out; its subject is the key as {@code bundle/key}. */
        KEY_REMOVED,
        /** The rebuild does not fit the release even so, and the packaged layout shows; its subject is why. */
        SET_ASIDE
    }

    /** One change, of {@code subject} as the change names it. */
    public record Adaptation(Change change, String subject) {}

    /**
     * A rebuild as it is drawn under this release.
     *
     * @param adaptations what was changed to carry it over, in order; empty when it was written over this release
     */
    public record Merged(Layout layout, List<Adaptation> adaptations) {

        public Merged {
            adaptations = List.copyOf(adaptations);
        }
    }

    /**
     * Returns the rebuild stored as {@code parts} and {@code links} as drawn over {@code packaged}.
     * It is carried over when {@code replaced}, the hash of the packaged layout it was written over, is another.
     */
    public static Merged merge(
            final Layout packaged, final List<Part> parts, final List<Element> links, final String replaced) {
        if (replaced.equals(LayoutSchema.hash(packaged))) {
            return new Merged(packaged.rebuilt(parts, links), List.of());
        }
        final Walk walk = new Walk(packaged, links);
        final List<Part> merged = walk.parts(parts);
        final List<String> buttons = new ArrayList<>();
        for (final Element element : packaged.elements()) {
            if (walk.kept.contains(element.id())) {
                continue;
            }
            walk.adapt(Change.ELEMENT_ADDED, element.id());
            switch (element.kind()) {
                case BUTTON -> buttons.add(element.id());
                case SELECT -> merged.add(Part.row(element.id()));
                case LIST -> merged.add(Part.text(Line.list(element.id())));
                case LANGUAGES ->
                    packaged.allParts().stream()
                            .filter(part -> part.type() == Part.Type.LANGUAGES
                                    && element.id().equals(part.element()))
                            .findFirst()
                            .ifPresent(merged::add);
                case LINK -> {}
            }
        }
        for (int from = 0; from < buttons.size(); from += ROW_BUTTONS) {
            merged.add(Part.row(buttons.subList(from, Math.min(buttons.size(), from + ROW_BUTTONS))
                    .toArray(String[]::new)));
        }
        final Layout layout = packaged.rebuilt(merged, links);
        final List<String> problems = LayoutCheck.rebuildProblems(packaged, layout);
        return problems.isEmpty()
                ? new Merged(layout, walk.adaptations)
                : new Merged(packaged, List.of(new Adaptation(Change.SET_ASIDE, problems.getFirst())));
    }

    /** One pass over a rebuild's tree, which keeps what the release still has. */
    private static final class Walk {

        private final Map<String, Element.Kind> declared = new LinkedHashMap<>();
        private final Set<String> keys;
        private final Set<String> kept = new HashSet<>();
        private final List<Adaptation> adaptations = new ArrayList<>();

        Walk(final Layout packaged, final List<Element> links) {
            packaged.elements().forEach(element -> declared.put(element.id(), element.kind()));
            links.forEach(link -> declared.putIfAbsent(link.id(), Element.Kind.LINK));
            keys = new HashSet<>(packaged.keys());
        }

        void adapt(final Change change, final String subject) {
            final Adaptation adaptation = new Adaptation(change, subject);
            if (!adaptations.contains(adaptation)) {
                adaptations.add(adaptation);
            }
        }

        List<Part> parts(final @Nullable List<Part> parts) {
            final List<Part> out = new ArrayList<>();
            Objects.requireNonNullElse(parts, List.<Part>of()).forEach(part -> out.addAll(part(part)));
            return out;
        }

        /** The part as the release keeps it: itself, what it held once its element is gone, or nothing. */
        private List<Part> part(final Part part) {
            return switch (part.type()) {
                case TEXT -> {
                    final List<Line> lines = new ArrayList<>();
                    for (final Line line : Objects.requireNonNullElse(part.lines(), List.<Line>of())) {
                        if (line.list() != null && !keep(line.list(), Element.Kind.LIST)) {
                            continue;
                        }
                        if (line.key() != null && !keys.contains(line.key())) {
                            adapt(Change.KEY_REMOVED, line.key());
                            continue;
                        }
                        lines.add(line);
                    }
                    yield lines.isEmpty() ? List.of() : List.of(with(part, part.parts(), lines, part.elements()));
                }
                case ROW -> {
                    final List<String> elements =
                            Objects.requireNonNullElse(part.elements(), List.<String>of()).stream()
                                    .filter(id -> keep(id, Element.Kind.BUTTON, Element.Kind.SELECT, Element.Kind.LINK))
                                    .toList();
                    yield elements.isEmpty() ? List.of() : List.of(with(part, part.parts(), part.lines(), elements));
                }
                case SECTION, LANGUAGES -> {
                    final List<Part> inner = parts(part.parts());
                    final String element = part.element();
                    if (element != null
                            && !(part.type() == Part.Type.SECTION
                                    ? keep(element, Element.Kind.BUTTON, Element.Kind.LINK)
                                    : keep(element, Element.Kind.LANGUAGES))) {
                        yield inner;
                    }
                    if (inner.isEmpty()) {
                        // Nothing left beside it: the element is placed anew at the end.
                        if (element != null) {
                            kept.remove(element);
                        }
                        yield List.of();
                    }
                    yield List.of(with(part, inner, part.lines(), part.elements()));
                }
                case CONTAINER -> {
                    final List<Part> inner = parts(part.parts());
                    yield inner.isEmpty() ? List.of() : List.of(with(part, inner, part.lines(), part.elements()));
                }
                case SEPARATOR, GALLERY -> List.of(part);
            };
        }

        /** Whether the release still declares the element as one of {@code kinds}; one it does not is removed. */
        private boolean keep(final String id, final Element.Kind... kinds) {
            final Element.Kind kind = declared.get(id);
            if (kind != null && List.of(kinds).contains(kind)) {
                kept.add(id);
                return true;
            }
            adapt(Change.ELEMENT_REMOVED, id);
            return false;
        }

        private static Part with(
                final Part part,
                final @Nullable List<Part> parts,
                final @Nullable List<Line> lines,
                final @Nullable List<String> elements) {
            return new Part(part.type(), part.accent(), parts, lines, part.element(), elements, part.media());
        }
    }
}
