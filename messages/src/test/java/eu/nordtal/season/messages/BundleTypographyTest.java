package eu.nordtal.season.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Holds every bundle to the separator rules of CONVENTIONS.md.
 *
 * A value never separates two things with a text symbol, and a comment never draws a banner.
 */
class BundleTypographyTest {

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern SPACED_HYPHEN = Pattern.compile("(^|\\s)-(\\s|$)");
    private static final Pattern DOUBLE_SPACE = Pattern.compile("\\s{2,}");
    private static final Pattern DASH = Pattern.compile("[\\u2013\\u2014]");
    private static final Pattern BANNER = Pattern.compile("-{4,}|={4,}|\\*{4,}|#{4,}|/{4,}|_{4,}|~{4,}");

    @Test
    void noValueSeparatesWithAHyphenOrAPipe() {
        assertEquals(
                Set.of(),
                valuesMatching(text -> SPACED_HYPHEN.matcher(text).find() || text.indexOf('|') >= 0, true),
                "a spaced hyphen or a pipe is a text symbol standing for a separator: use a comma, a colon, a new"
                        + " sentence, two lines or two pills");
    }

    @Test
    void noValueAlignsWithDoubleSpaces() {
        assertEquals(
                Set.of(),
                valuesMatching(text -> DOUBLE_SPACE.matcher(text).find(), false),
                "two spaces in a row is a layout the font does not promise: separate with one space or two lines");
    }

    @Test
    void noValueHoldsADashEvenAsAnEscape() {
        assertEquals(
                Set.of(),
                valuesMatching(text -> DASH.matcher(text).find(), false),
                "a decoded en or em dash is the dash the conventions forbid, written as an escape: use a hyphen"
                        + " for a range");
    }

    @Test
    void noCommentIsABanner() {
        final Set<String> banners = new TreeSet<>();
        BundleFiles.bundles().forEach((bundle, languages) -> {
            for (final String language : languages) {
                for (final String line : BundleFiles.lines(bundle, language)) {
                    final String trimmed = line.stripLeading();
                    if ((trimmed.startsWith("#") || trimmed.startsWith("!"))
                            && BANNER.matcher(trimmed).find()) {
                        banners.add(bundle + "/" + language + ".properties: " + trimmed);
                    }
                }
            }
        });
        assertEquals(Set.of(), banners, "a banner comment (# ----- section) is decoration: name the section plainly");
    }

    /** Returns {@code bundle/language key} of every value the test matches, with its tags removed when asked. */
    private static Set<String> valuesMatching(final Predicate<String> test, final boolean withoutTags) {
        final Set<String> found = new TreeSet<>();
        BundleFiles.bundles().forEach((bundle, languages) -> {
            for (final String language : languages) {
                final Properties texts = BundleFiles.load(bundle, language);
                for (final String key : texts.stringPropertyNames()) {
                    final String value = texts.getProperty(key);
                    if (test.test(withoutTags ? TAG.matcher(value).replaceAll("") : value)) {
                        found.add(bundle + "/" + language + ".properties " + key);
                    }
                }
            }
        });
        return found;
    }
}
