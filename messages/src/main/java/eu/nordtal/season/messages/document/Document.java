package eu.nordtal.season.messages.document;

import eu.nordtal.season.common.json.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

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

    public static Document decode(final String json) {
        return Json.decode(json, Document.class);
    }

    public String json() {
        return Json.encode(this);
    }
}
