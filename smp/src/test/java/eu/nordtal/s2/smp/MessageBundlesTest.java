package eu.nordtal.s2.smp;

import eu.nordtal.s2.common.message.Messages;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the SMP's two language files stay the same file in two languages.
 *
 * <p>The same guard {@code hunger-games} grew on 2026-09-01, for the same reason: {@code Messages}
 * degrades to the key rather than throwing, so a key present in one language and absent in the
 * other reaches a player as the literal string {@code smp.balloon.locked} at the worst possible
 * moment. That is the right runtime behaviour and exactly why it has to fail here instead.
 *
 * <p>Placeholder symmetry is checked as well: a translation that spells {@code {minutes}} as
 * {@code {minute}} prints the braces to somebody rather than a number.
 */
class MessageBundlesTest {

    private static final String ROOT = "messages/smp";

    private final Messages messages = Messages.load(MessageBundlesTest.class.getClassLoader(),
            ROOT, Locale.ENGLISH, Locale.GERMAN);

    /**
     * The one message in this bundle that carries a MiniMessage tag rather than plain text.
     *
     * <p>The wheel used to print {@code IRON_INGOT} - the enum name - at every player in both
     * languages. It now passes {@code Material#translationKey()} into a {@code <lang:...>} tag, so
     * the client renders the item's own name in the client's own language and neither bundle has
     * to carry an item list. That is a trick, and a trick nothing exercises is a trick that breaks
     * quietly: {@code MessageRenderer} substitutes before it deserialises, so a change to either
     * half would turn this back into literal text rather than into an error.
     */
    @Test
    void theWheelNamesItsPrizeInTheClientsOwnLanguage() {
        for (final Locale locale : java.util.List.of(Locale.ENGLISH, Locale.GERMAN)) {
            final net.kyori.adventure.text.Component rendered =
                    eu.nordtal.s2.common.message.MessageRenderer.of(messages)
                            .format(locale, "smp.wheel.won", "amount", 3,
                                    "item", "block.minecraft.stone");
            final java.util.List<net.kyori.adventure.text.Component> parts =
                    new java.util.ArrayList<>();
            flatten(rendered, parts);
            assertTrue(parts.stream().anyMatch(part ->
                            part instanceof net.kyori.adventure.text.TranslatableComponent tr
                                    && "block.minecraft.stone".equals(tr.key())),
                    locale + ": the item has to arrive as a translatable component. It came out as "
                            + rendered);
        }
    }

    private static void flatten(final net.kyori.adventure.text.Component component,
                                final java.util.List<net.kyori.adventure.text.Component> out) {
        out.add(component);
        component.children().forEach(child -> flatten(child, out));
    }

    @Test
    void bothBundlesAreLoaded() {
        assertTrue(messages.languages().contains("en"));
        assertTrue(messages.languages().contains("de"),
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
            for (final Locale locale : new Locale[]{Locale.ENGLISH, Locale.GERMAN}) {
                assertTrue(messages.hasTranslation(locale, key), key + " does not resolve in " + locale);
            }
        }
    }

    @Test
    void thePlaceholdersOfATranslationMatchItsOriginal() throws IOException {
        final Properties english = load("en");
        final Properties german = load("de");

        for (final String key : english.stringPropertyNames()) {
            assertEquals(placeholders(english.getProperty(key)), placeholders(german.getProperty(key)),
                    key + " uses different placeholders in the two languages - one of them will "
                            + "print a literal {name} to a player");
        }
    }

    /**
     * The component slots, which are the other half of {@link #thePlaceholdersOfATranslationMatchItsOriginal()}.
     *
     * <p>A {@code <_name>} tag is where something that is already a component goes - the sender's
     * flag and crest, vanilla's own death message, an advancement's title. It is written with a
     * leading underscore precisely so a test can find it: every other angle bracket in these files
     * is a MiniMessage style tag, which the two languages are entitled to differ on.
     *
     * <p>The failure it catches is worse than a printed {@code {name}}. A translation that drops
     * {@code <_death>} does not print the tag - MiniMessage silently renders nothing for an
     * unresolved tag, so German readers would get a death line with no death in it and the server
     * would log nothing at all.
     */
    @Test
    void theComponentSlotsOfATranslationMatchItsOriginal() throws IOException {
        final Properties english = load("en");
        final Properties german = load("de");

        for (final String key : english.stringPropertyNames()) {
            assertEquals(slots(english.getProperty(key)), slots(german.getProperty(key)),
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
        try (InputStream stream = MessageBundlesTest.class.getClassLoader()
                .getResourceAsStream(ROOT + "/" + language + ".properties")) {
            assertNotNull(stream, "no " + language + ".properties on the test classpath");
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
