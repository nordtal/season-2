package eu.nordtal.s2.smp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.messages.Messages;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * That the SMP's two language files stay the same file in two languages, placeholders included.
 *
 * {@code Messages} degrades to the key rather than throwing, so a missing key has to fail here instead.
 */
class MessageBundlesTest {

    private static final String ROOT = "messages/smp";

    private final Messages messages =
            Messages.load(MessageBundlesTest.class.getClassLoader(), ROOT, Locale.ENGLISH, Locale.GERMAN);

    /**
     * The wheel's prize arrives as a translated item name, not literal text.
     */
    @Test
    void theWheelNamesItsPrizeInTheClientsOwnLanguage() {
        for (final Locale locale : java.util.List.of(Locale.ENGLISH, Locale.GERMAN)) {
            final net.kyori.adventure.text.Component rendered = eu.nordtal.s2.messagerendering.MessageRenderer.of(
                            messages)
                    .format(
                            locale,
                            SmpMessages.MESSAGES
                                    .smp()
                                    .wheel()
                                    .won(3, eu.nordtal.s2.messages.value.GameContent.of("block.minecraft.stone")));
            final java.util.List<net.kyori.adventure.text.Component> parts = new java.util.ArrayList<>();
            flatten(rendered, parts);
            assertTrue(
                    parts.stream()
                            .anyMatch(part -> part instanceof net.kyori.adventure.text.TranslatableComponent tr
                                    && "block.minecraft.stone".equals(tr.key())),
                    locale + ": the item has to arrive as a translatable component. It came out as " + rendered);
        }
    }

    private static void flatten(
            final net.kyori.adventure.text.Component component,
            final java.util.List<net.kyori.adventure.text.Component> out) {
        out.add(component);
        component.children().forEach(child -> flatten(child, out));
    }

    @Test
    void bothBundlesAreLoaded() {
        assertTrue(messages.languages().contains("en"));
        assertTrue(
                messages.languages().contains("de"),
                "German is not a fallback language, it is one of the two the season ships");
    }

    @Test
    void everyKeyExistsInBothLanguages() throws IOException {
        final Set<String> english = keysOf("en");
        final Set<String> german = keysOf("de");

        final Set<String> onlyEnglish = new TreeSet<>(english);
        onlyEnglish.removeAll(german);
        final Set<String> onlyGerman = new TreeSet<>(german);
        onlyGerman.removeAll(english);

        assertEquals(Set.of(), onlyEnglish, "keys with no German translation");
        assertEquals(Set.of(), onlyGerman, "German keys with no English original");
    }

    @Test
    void everyKeyResolvesThroughMessagesInBothLanguages() throws IOException {
        for (final String key : keysOf("en")) {
            for (final Locale locale : new Locale[] {Locale.ENGLISH, Locale.GERMAN}) {
                assertTrue(messages.hasTranslation(locale, key), key + " does not resolve in " + locale);
            }
        }
    }

    @Test
    void thePlaceholdersOfATranslationMatchItsOriginal() throws IOException {
        final Properties english = load("en");
        final Properties german = load("de");

        for (final String key : english.stringPropertyNames()) {
            assertEquals(
                    placeholders(english.getProperty(key)),
                    placeholders(german.getProperty(key)),
                    key + " uses different placeholders in the two languages - one of them will "
                            + "print a literal {name} to a player");
        }
    }

    /**
     * The component slots: a {@code <_name>} tag dropped by a translation renders as nothing, silently.
     *
     * The leading underscore is what tells them apart from style tags, which the languages may differ on.
     */
    @Test
    void theComponentSlotsOfATranslationMatchItsOriginal() throws IOException {
        final Properties english = load("en");
        final Properties german = load("de");

        for (final String key : english.stringPropertyNames()) {
            assertEquals(
                    slots(english.getProperty(key)),
                    slots(german.getProperty(key)),
                    key + " uses different <_component> slots in the two languages - an unresolved"
                            + " slot renders as nothing at all, in silence");
        }
    }

    private static Set<String> slots(final String text) {
        final Set<String> found = new TreeSet<>();
        final java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("<(_[a-zA-Z0-9_-]+)>").matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static Set<String> placeholders(final String text) {
        final Set<String> found = new TreeSet<>();
        final java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\{([a-zA-Z0-9_-]+)}").matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static Set<String> keysOf(final String language) throws IOException {
        return new TreeSet<>(load(language).stringPropertyNames());
    }

    private static Properties load(final String language) throws IOException {
        final Properties properties = new Properties();
        try (InputStream stream =
                MessageBundlesTest.class.getClassLoader().getResourceAsStream(ROOT + "/" + language + ".properties")) {
            assertNotNull(stream, "no " + language + ".properties on the test classpath");
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
