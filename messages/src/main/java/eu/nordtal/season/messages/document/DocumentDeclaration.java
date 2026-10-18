package eu.nordtal.season.messages.document;

import eu.nordtal.season.messages.context.Contexts;
import eu.nordtal.season.messages.text.Declaration;
import eu.nordtal.season.messages.value.Kind;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** What a document's file must fit, as the build writes it into {@code declarations.json}. */
public record DocumentDeclaration(
        String namespace,
        String name,
        String area,
        DocumentSpec.Shape shape,
        Map<String, Value> values,
        List<Slot> slots) {

    /** Names a button's label where a text names it as {@code <slot>.label}. */
    static final String LABEL = "label";

    public DocumentDeclaration {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        slots = List.copyOf(slots);
    }

    public String id() {
        return namespace + "/" + name;
    }

    public Optional<Slot> slot(final String id) {
        return slots.stream().filter(slot -> slot.id().equals(id)).findFirst();
    }

    /** Returns every value a text may name: the declared ones, each button's label and the global roles. */
    public Map<String, Value> named() {
        final Map<String, Value> named = new LinkedHashMap<>(values);
        slots.stream()
                .filter(slot -> slot.type() == Slot.Type.BUTTON)
                .forEach(slot -> named.put(slot.id() + "." + LABEL, new Value(Kind.TEXT.token(), "Button", null)));
        Contexts.GLOBALS.forEach((role, type) -> {
            if (named.keySet().stream().noneMatch(name -> name.startsWith(role + "."))) {
                Contexts.attributes(type)
                        .forEach(attribute -> named.putIfAbsent(
                                role + "." + attribute.name(),
                                new Value(attribute.kind().token(), attribute.example(), null)));
            }
        });
        return named;
    }

    /** Returns what a text in a place with {@code limit} characters may use, with an item's own values. */
    public Declaration text(final Map<String, Value> item, final int limit, final boolean oneLine) {
        return text(item, limit, oneLine, false);
    }

    /** @param markup whether the text is MiniMessage, as a game menu's title and items are */
    public Declaration text(
            final Map<String, Value> item, final int limit, final boolean oneLine, final boolean markup) {
        final Map<String, Value> all = new LinkedHashMap<>(named());
        all.putAll(item);
        final Map<String, Kind> kinds = new HashMap<>();
        final Map<String, String> examples = new HashMap<>();
        all.forEach((name, value) -> {
            kinds.put(name, Kind.byToken(value.kind()).orElse(Kind.TEXT));
            examples.put(name, value.example());
        });
        return new Declaration(kinds, Set.of(), Set.of(), markup, limit, examples, oneLine);
    }

    public Map<String, Kind> kinds(final Map<String, Value> item) {
        return text(item, 0, false).values();
    }

    public Slot requireSlot(final String id) {
        return slot(id).orElseThrow(() -> new IllegalArgumentException(id() + " declares no slot " + id));
    }
}
