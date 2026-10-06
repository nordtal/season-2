package eu.nordtal.season.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messages.text.MessageText;
import eu.nordtal.season.messages.text.Node;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Checks that every bundle in the repository is complete in both languages and agrees with the numbers it shows.
 *
 * The English-only bundles ship one language. A missing German key falls back to English silently, so the tree is
 * walked to cover new modules without a line here.
 */
class EveryBundleIsCompleteTest {

    /** The bundles known to exist, a floor that fails a walk which silently finds nothing. */
    private static final Set<String> KNOWN = Set.of(
            "database/src/main/resources/messages/database",
            "discord-bot/src/main/resources/messages/access",
            "hunger-games/src/main/resources/messages/hunger-games",
            "limbo/src/main/resources/messages/limbo",
            "proxy/src/main/resources/messages/proxy",
            "paper-common/src/main/resources/messages/paper-common",
            "smp/src/main/resources/messages/smp");

    /**
     * A counted noun after a number, past the tags that close around the number; parsed without markup, a tag is text.
     *
     * German nouns alike in both numbers are left out, but for those whose verb follows the count.
     */
    private static final Pattern COUNTED = Pattern.compile("(</?[a-z][^>]*>)*\\s+("
            + "seconds?|minutes?|hours?|days?|players?|participants?|teams?|members?|milestones?|kills?"
            + "|Sekunden?|Minuten?|Stunden?|Tag(?:en?)?|Teams?|Teilnehmende|Teilnehmer|Meilenstein(?:en?)?)\\b");

    /** The bundles only admins read, which are English: Steward's page, the words it shares, and the check's. */
    private static final Set<String> ENGLISH_ONLY = Set.of(
            "steward/src/main/resources/messages/steward",
            "database/src/main/resources/messages/admin",
            "messages/src/main/resources/messages/check");

    @Test
    void theWalkFindsEveryKnownBundle() {
        assertTrue(
                BundleFiles.bundles().keySet().containsAll(KNOWN)
                        && BundleFiles.bundles().keySet().containsAll(ENGLISH_ONLY),
                "the walk does not find every known bundle. Missing: "
                        + missingFrom(BundleFiles.bundles().keySet()) + " or one of " + ENGLISH_ONLY);
    }

    @Test
    void aBundleShipsBothLanguagesBecauseGermanIsNotAFallback() {
        final Map<String, Set<String>> incomplete = new TreeMap<>();
        BundleFiles.bundles().forEach((name, languages) -> {
            if (!languages.equals(ENGLISH_ONLY.contains(name) ? Set.of("en") : Set.of("en", "de"))) {
                incomplete.put(name, languages);
            }
        });
        assertEquals(
                Map.of(), incomplete, "the season ships two languages, and a bundle with one of them is half a bundle");
    }

    @Test
    void everyKeyExistsInBothLanguages() {
        final Map<String, Set<String>> untranslated = new TreeMap<>();
        for (final String name : translated()) {
            final Set<String> english = keysOf(name, "en");
            final Set<String> german = keysOf(name, "de");

            final Set<String> onlyEnglish = new TreeSet<>(english);
            onlyEnglish.removeAll(german);
            final Set<String> onlyGerman = new TreeSet<>(german);
            onlyGerman.removeAll(english);

            if (!onlyEnglish.isEmpty()) {
                untranslated.put(name + " has no German for", onlyEnglish);
            }
            if (!onlyGerman.isEmpty()) {
                untranslated.put(name + " has German with no English original for", onlyGerman);
            }
        }
        assertEquals(
                Map.of(),
                untranslated,
                "a key with no translation is not an error anybody sees - English is the fallback,"
                        + " so it is answered in English and nothing anywhere says so");
    }

    @Test
    void aTranslationUsesTheSamePlaceholdersAsItsOriginal() {
        final Map<String, String> wrong = new TreeMap<>();
        for (final String name : translated()) {
            final Properties english = BundleFiles.load(name, "en");
            final Properties german = BundleFiles.load(name, "de");

            for (final String key : english.stringPropertyNames()) {
                final String translation = german.getProperty(key);
                if (translation == null) {
                    continue; // everyKeyExistsInBothLanguages says this, and says it better
                }
                final Set<String> original = placeholders(english.getProperty(key));
                if (!original.equals(placeholders(translation))) {
                    wrong.put(name + "/" + key, "en " + original + " vs de " + placeholders(translation));
                }
            }
        }
        assertEquals(Map.of(), wrong, "one of these prints a literal {name} to somebody, and the other does not");
    }

    @Test
    void aNounAfterANumberAgreesWithIt() {
        final Set<String> wrong = new TreeSet<>();
        BundleFiles.bundles().forEach((name, languages) -> {
            for (final String language : languages) {
                final Properties texts = BundleFiles.load(name, language);
                for (final String key : texts.stringPropertyNames()) {
                    if (nounAfterUnchosenNumber(
                            MessageText.parse(texts.getProperty(key), false).nodes(), Set.of())) {
                        wrong.add(name + "/" + language + ".properties " + key);
                    }
                }
            }
        });
        assertEquals(
                Set.of(),
                wrong,
                "a fixed noun after a number reads \"1 seconds\" or \"1 players\" once the number is one:"
                        + " choose it, as in {seconds, plural, one {second} other {seconds}}");
    }

    /**
     * Returns whether a value is followed, past any tags, by a counted noun that no plural choice on it chose.
     *
     * @param chosen the values an enclosing plural already chose on, which may be followed by their noun
     */
    private static boolean nounAfterUnchosenNumber(final List<Node> nodes, final Set<String> chosen) {
        boolean afterNumber = false;
        for (final Node node : nodes) {
            switch (node) {
                case Node.Value value -> afterNumber = !chosen.contains(value.name());
                case Node.Literal literal -> {
                    if (afterNumber && COUNTED.matcher(literal.text()).lookingAt()) {
                        return true;
                    }
                    afterNumber = false;
                }
                case Node.Choice choice -> {
                    final Set<String> inside = new TreeSet<>(chosen);
                    if (choice.plural()) {
                        inside.add(choice.name());
                    }
                    for (final List<Node> branch : choice.cases().values()) {
                        if (nounAfterUnchosenNumber(branch, inside)) {
                            return true;
                        }
                    }
                    afterNumber = false;
                }
                default -> afterNumber = false;
            }
        }
        return false;
    }

    /** Every bundle that ships German, which is every one but the English-only ones. */
    private static Set<String> translated() {
        final Set<String> translated = new TreeSet<>(BundleFiles.bundles().keySet());
        translated.removeAll(ENGLISH_ONLY);
        return translated;
    }

    private static Set<String> missingFrom(final Set<String> found) {
        final Set<String> missing = new TreeSet<>(KNOWN);
        missing.removeAll(found);
        return missing;
    }

    private static Set<String> keysOf(final String bundle, final String language) {
        return new TreeSet<>(BundleFiles.load(bundle, language).stringPropertyNames());
    }

    /** Returns every name a text shows or chooses on, as the parser reads it; tags are left as characters. */
    private static Set<String> placeholders(final String text) {
        final Set<String> names = new TreeSet<>();
        collect(MessageText.parse(text, false).nodes(), names);
        return names;
    }

    private static void collect(final List<Node> nodes, final Set<String> into) {
        for (final Node node : nodes) {
            switch (node) {
                case Node.Value value -> into.add(value.name());
                case Node.Choice choice -> {
                    into.add(choice.name());
                    choice.cases().values().forEach(inner -> collect(inner, into));
                }
                default -> {
                    // Literals, a plural's # and tags name nothing.
                }
            }
        }
    }
}
