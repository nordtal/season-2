package eu.nordtal.season.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Holds every bundle to the spelling of CONVENTIONS.md: Nordtal in prose, nordtal.eu as the address. */
class NetworkNameTest {

    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    /** A host or a path that carries the name, such as {@code nordtal.eu} or {@code steward.nordtal.eu}. */
    private static final Pattern ADDRESS = Pattern.compile("[\\w.:/-]*nordtal\\.[a-z]{2,}[\\w./-]*");

    /** A select's case, which names a value, not the network: {@code select, nordtal {Nordtal}}. */
    private static final Pattern SELECT_CASE = Pattern.compile("\\bnordtal\\s*\\{");

    private static final Pattern LOWER_CASE = Pattern.compile("\\bnordtal\\b");

    @Test
    void noTextSpellsTheNameInLowerCaseOutsideAnAddress() {
        final Set<String> found = new TreeSet<>();
        BundleFiles.bundles().forEach((bundle, languages) -> {
            for (final String language : languages) {
                final Properties values = BundleFiles.load(bundle, language);
                for (final String key : values.stringPropertyNames()) {
                    if (LOWER_CASE.matcher(prose(values.getProperty(key))).find()) {
                        found.add(bundle + "/" + language + ": " + key);
                    }
                }
            }
        });
        assertEquals(Set.of(), found, "the network is Nordtal in prose and nordtal.eu as an address");
    }

    /** The words a player reads: no tags, no addresses, no select cases. */
    private static String prose(final String value) {
        final String untagged = TAG.matcher(value).replaceAll(" ");
        return SELECT_CASE.matcher(ADDRESS.matcher(untagged).replaceAll(" ")).replaceAll(" ");
    }
}
