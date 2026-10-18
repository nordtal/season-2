package eu.nordtal.season.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.messages.text.Typography;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** A value never separates two items with a text symbol. A comment never draws a banner. */
class BundleTypographyTest {

    private static final Pattern BANNER = Pattern.compile("-{4,}|={4,}|\\*{4,}|#{4,}|/{4,}|_{4,}|~{4,}");

    @Test
    void noValueSeparatesWithAHyphenOrAPipe() {
        assertEquals(Set.of(), valuesBreaking(Typography.SEPARATOR), Typography.SEPARATOR);
    }

    @Test
    void noValueAlignsWithDoubleSpaces() {
        assertEquals(Set.of(), valuesBreaking(Typography.ALIGNED), Typography.ALIGNED);
    }

    @Test
    void noValueHoldsADashEvenAsAnEscape() {
        assertEquals(Set.of(), valuesBreaking(Typography.DASHED), Typography.DASHED);
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

    private static Set<String> valuesBreaking(final String rule) {
        final Set<String> found = new TreeSet<>();
        BundleFiles.bundles().forEach((bundle, languages) -> {
            for (final String language : languages) {
                final Properties texts = BundleFiles.load(bundle, language);
                for (final String key : texts.stringPropertyNames()) {
                    final String value = texts.getProperty(key);
                    if (Typography.problems(value).contains(rule)) {
                        found.add(bundle + "/" + language + ".properties " + key);
                    }
                }
            }
        });
        return found;
    }
}
