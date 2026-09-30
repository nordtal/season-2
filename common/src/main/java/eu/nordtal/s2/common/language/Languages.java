package eu.nordtal.s2.common.language;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * The languages the network speaks, as language tags, the fallback first.
 *
 * @param tags lowercase language tags, the first one {@link Locales#DEFAULT}'s
 */
public record Languages(List<String> tags) {

    /** The network's languages: every Minecraft process loads and renders these. */
    public static final Languages NETWORK = new Languages(List.of("en", "de"));

    public Languages {
        tags = List.copyOf(tags);
        if (tags.isEmpty() || !tags.getFirst().equals(Locales.tag(Locales.DEFAULT))) {
            throw new IllegalArgumentException("the fallback language comes first: " + tags);
        }
        if (new HashSet<>(tags).size() != tags.size()) {
            throw new IllegalArgumentException("a language is listed twice: " + tags);
        }
    }

    /** Returns the languages as locales, in order, for a bundle to load. */
    public Locale[] locales() {
        return tags.stream().map(Locales::parse).toArray(Locale[]::new);
    }

    /** Returns the languages after the fallback, which a screen for nobody in particular shows underneath. */
    public List<Locale> others() {
        return tags.subList(1, tags.size()).stream().map(Locales::parse).toList();
    }
}
