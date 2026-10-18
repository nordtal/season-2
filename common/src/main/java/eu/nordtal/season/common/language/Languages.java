package eu.nordtal.season.common.language;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** The network's languages as language tags, the fallback first. */
public record Languages(List<String> tags) {

    public Languages {
        tags = List.copyOf(tags);
        if (tags.isEmpty() || !tags.getFirst().equals(Locales.tag(Locales.DEFAULT))) {
            throw new IllegalArgumentException("the fallback language comes first: " + tags);
        }
        if (new HashSet<>(tags).size() != tags.size()) {
            throw new IllegalArgumentException("a language is listed twice: " + tags);
        }
    }

    public Locale[] locales() {
        return tags.stream().map(Locales::parse).toArray(Locale[]::new);
    }

    /** Returns the languages after the fallback. */
    public List<Locale> others() {
        return tags.subList(1, tags.size()).stream().map(Locales::parse).toList();
    }
}
