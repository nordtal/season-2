package eu.nordtal.season.messages.document;

import eu.nordtal.season.messages.text.MessageCheck;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Adapts an admin's document to a release that changed the packaged document it was written over.
 * A slot the release removed is taken out, and a required slot it added is placed at the end as the release places it.
 * A document that still does not fit is set aside for the packaged one.
 */
public final class DocumentMerge {

    private DocumentMerge() {}

    public enum Change {
        /** The release added a required slot, placed at the end. The subject is its id. */
        SLOT_ADDED,
        /** The release removed the slot or gave it another type. It is taken out. The subject is its id. */
        SLOT_REMOVED,
        /** The document does not fit the release, so the packaged document shows. The subject is the reason. */
        SET_ASIDE
    }

    public record Adaptation(Change change, String subject) {}

    /** @param adaptations the changes made, in order. Empty when the document was written over this release. */
    public record Merged(Document document, List<Adaptation> adaptations) {

        public Merged {
            adaptations = List.copyOf(adaptations);
        }
    }

    /** Returns the admin's document adapted to {@code packaged}, unless {@code replaced} is the packaged hash. */
    public static Merged merge(final Documents.Packaged packaged, final Document written, final String replaced) {
        if (replaced.equals(packaged.hash())) {
            return new Merged(written, List.of());
        }
        final Walk walk = new Walk(packaged.declaration());
        final List<Part> merged = walk.parts(written.parts());
        final List<Item> buttons = new ArrayList<>();
        for (final Slot slot : packaged.declaration().slots()) {
            if (!slot.required() || walk.kept.contains(slot.id())) {
                continue;
            }
            final Optional<Part> placement = placement(packaged.document(), slot);
            if (placement.isEmpty()) {
                continue;
            }
            walk.adapt(Change.SLOT_ADDED, slot.id());
            switch (slot.type()) {
                case BUTTON -> buttons.add(item(placement.get(), slot.id()));
                case SELECT, INPUT -> merged.add(Part.row(List.of(item(placement.get(), slot.id()))));
                case LIST ->
                    merged.add(Part.text(placement.get().allLines().stream()
                            .filter(line -> slot.id().equals(line.list()))
                            .toList()));
                case LANGUAGES -> merged.add(placement.get());
            }
        }
        for (int from = 0; from < buttons.size(); from += DocumentCheck.ROW_BUTTONS) {
            merged.add(Part.row(buttons.subList(from, Math.min(buttons.size(), from + DocumentCheck.ROW_BUTTONS))));
        }
        final Document document = new Document(merged);
        final List<String> problems =
                DocumentCheck.errors(packaged.declaration(), document, MessageCheck.Mode.OVERRIDE);
        return problems.isEmpty()
                ? new Merged(document, walk.adaptations)
                : new Merged(packaged.document(), List.of(new Adaptation(Change.SET_ASIDE, problems.getFirst())));
    }

    /** Returns the packaged part that places the slot. */
    private static Optional<Part> placement(final Document packaged, final Slot slot) {
        return packaged.allParts().stream()
                .filter(part -> slot.id().equals(part.slot())
                        || part.allLines().stream().anyMatch(line -> slot.id().equals(line.list()))
                        || part.allItems().stream().anyMatch(item -> slot.id().equals(item.slot()))
                        || (part.button() != null
                                && slot.id().equals(part.button().slot())))
                .findFirst();
    }

    private static Item item(final Part part, final String slot) {
        if (part.button() != null && slot.equals(part.button().slot())) {
            return part.button();
        }
        return part.allItems().stream()
                .filter(item -> slot.equals(item.slot()))
                .findFirst()
                .orElseThrow();
    }

    private static final class Walk {

        private final DocumentDeclaration declaration;
        private final Set<String> kept = new HashSet<>();
        private final List<Adaptation> adaptations = new ArrayList<>();

        Walk(final DocumentDeclaration declaration) {
            this.declaration = declaration;
        }

        void adapt(final Change change, final String subject) {
            final Adaptation adaptation = new Adaptation(change, subject);
            if (!adaptations.contains(adaptation)) {
                adaptations.add(adaptation);
            }
        }

        List<Part> parts(final List<Part> parts) {
            final List<Part> out = new ArrayList<>();
            parts.forEach(part -> out.addAll(part(part)));
            return out;
        }

        /** Returns the part as the release keeps it: itself, what remains of it, or nothing. */
        private List<Part> part(final Part part) {
            return switch (part.type()) {
                case TEXT -> {
                    final List<Line> lines = part.allLines().stream()
                            .filter(line -> line.list() == null || keep(line.list(), Slot.Type.LIST))
                            .toList();
                    yield lines.isEmpty()
                            ? List.of()
                            : List.of(with(part, part.parts(), lines, part.button(), part.items()));
                }
                case ROW -> {
                    final List<Item> items = part.allItems().stream()
                            .filter(item -> item.slot() == null
                                    || keep(item.slot(), Slot.Type.BUTTON, Slot.Type.SELECT, Slot.Type.INPUT))
                            .toList();
                    yield items.isEmpty() ? List.of() : List.of(with(part, part.parts(), part.lines(), null, items));
                }
                case SECTION -> {
                    final List<Part> inner = parts(part.inner());
                    final Item button = part.button();
                    if (button != null && button.slot() != null && !keep(button.slot(), Slot.Type.BUTTON)) {
                        yield inner;
                    }
                    yield inner.isEmpty() ? List.of() : List.of(with(part, inner, part.lines(), button, part.items()));
                }
                case LANGUAGES -> {
                    final List<Part> inner = parts(part.inner());
                    if (!keep(Objects.requireNonNullElse(part.slot(), ""), Slot.Type.LANGUAGES)) {
                        yield inner;
                    }
                    yield inner.isEmpty() ? List.of() : List.of(with(part, inner, part.lines(), null, part.items()));
                }
                case CONTAINER -> {
                    final List<Part> inner = parts(part.inner());
                    yield inner.isEmpty() ? List.of() : List.of(with(part, inner, part.lines(), null, part.items()));
                }
                case SEPARATOR, GALLERY, FILE -> List.of(part);
            };
        }

        /** Returns whether the release declares the slot as one of {@code types}, and records it as removed if not. */
        private boolean keep(final String id, final Slot.Type... types) {
            final Optional<Slot> slot = declaration.slot(id);
            if (slot.isPresent() && List.of(types).contains(slot.get().type())) {
                kept.add(id);
                return true;
            }
            adapt(Change.SLOT_REMOVED, id);
            return false;
        }

        private static Part with(
                final Part part,
                final @Nullable List<Part> parts,
                final @Nullable List<Line> lines,
                final @Nullable Item button,
                final @Nullable List<Item> items) {
            return new Part(
                    part.type(),
                    part.when(),
                    part.accent(),
                    parts,
                    lines,
                    button,
                    part.thumbnail(),
                    part.media(),
                    items,
                    part.slot());
        }
    }
}
