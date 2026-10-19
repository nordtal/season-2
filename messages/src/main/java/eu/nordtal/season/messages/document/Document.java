package eu.nordtal.season.messages.document;

import eu.nordtal.season.common.json.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/** One output of a module as a reader sees it: a tree of parts with every text in every language. */
public record Document(List<Part> parts) {

    public Document {
        parts = List.copyOf(Objects.requireNonNullElse(parts, List.of()));
    }

    /** Returns every part, each before the parts it holds. */
    public List<Part> allParts() {
        final List<Part> all = new ArrayList<>();
        parts.forEach(part -> part.collect(all));
        return all;
    }

    /** Returns every row item and section button in the order placed. */
    public List<Item> items() {
        final List<Item> items = new ArrayList<>();
        for (final Part part : allParts()) {
            if (part.button() != null) {
                items.add(part.button());
            }
            items.addAll(part.allItems());
        }
        return items;
    }

    /** Returns every text the document holds. */
    public List<Text> texts() {
        final List<Text> texts = new ArrayList<>();
        allParts().forEach(part -> part.allLines().forEach(line -> texts.add(line.text())));
        for (final Item item : items()) {
            for (final Text text : new Text[] {item.label(), item.placeholder(), item.option()}) {
                if (text != null) {
                    texts.add(text);
                }
            }
        }
        return texts;
    }

    /** Returns every language a text of the document is written in. */
    public Set<String> languages() {
        final Set<String> languages = new TreeSet<>();
        texts().forEach(text -> languages.addAll(text.languages().keySet()));
        return languages;
    }

    /** Returns the names of the shared parts the document holds a copy of. */
    public Set<String> sharedParts() {
        final Set<String> names = new TreeSet<>();
        allParts().forEach(part -> {
            if (part.shared() != null) {
                names.add(part.shared());
            }
        });
        return names;
    }

    /** Returns the document with every copy of shared part {@code name} replaced; see {@link Part#withSharedPart}. */
    public Document withSharedPart(final String name, final @Nullable Part saved) {
        return new Document(
                parts.stream().map(part -> part.withSharedPart(name, saved)).toList());
    }

    public static Document decode(final String json) {
        return Json.decode(json, Document.class);
    }

    public String json() {
        return Json.encode(this);
    }
}
