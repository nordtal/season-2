package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.message.Messages;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The help output's shape, which is four message keys and no code.
 *
 * <h2>Why that is worth pinning</h2>
 * The point of putting the whole format in the bundle is that an operator can change it without a
 * release - re-order the parts, drop the explanation, change the separator. What makes that safe is
 * that the adapter supplies exactly the placeholders these keys name and no others: an override that
 * names {@code {description}} would otherwise print the literal string {@code {description}} into
 * somebody's chat, and the only way to find out would be to mistype a command on a live server.
 */
class HelpFormatTest {

    private static final String ROOT = "messages/commands";

    private final Messages messages = Messages.load(HelpFormatTest.class.getClassLoader(),
            ROOT, Locale.ENGLISH, Locale.GERMAN);

    /** What {@code PaperCommands} actually fills in, per key. */
    private static final Map<String, List<String>> SUPPLIED = Map.of(
            "command.help.header", List.of("command"),
            "command.help.line", List.of("usage", "what"),
            "command.help.usage", List.of("usage"),
            "command.help.what", List.of("what"),
            "command.help.nothing", List.of());

    @Test
    @DisplayName("every format key exists in both languages")
    void theFormatIsComplete() {
        for (final String key : SUPPLIED.keySet()) {
            for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
                assertTrue(messages.hasTranslation(locale, key),
                        key + " is missing in " + locale.getLanguage()
                                + " - the help output would print the key itself at somebody who has"
                                + " just mistyped a command");
            }
        }
    }

    @Test
    @DisplayName("no format key names a placeholder the adapter does not supply")
    void everyPlaceholderIsFilled() {
        for (final Map.Entry<String, List<String>> entry : SUPPLIED.entrySet()) {
            for (final Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
                final String text = messages.get(locale, entry.getKey());
                for (final String placeholder : placeholders(text)) {
                    assertTrue(entry.getValue().contains(placeholder),
                            entry.getKey() + " (" + locale.getLanguage() + ") names {" + placeholder
                                    + "}, which nothing fills - it would reach a reader verbatim");
                }
            }
        }
    }

    @Test
    @DisplayName("the list line keeps its indent, which needs escaping to survive Properties.load")
    void theIndentIsEscaped() throws IOException {
        // Properties.load strips unescaped leading whitespace from a value. The unescaped version
        // parses, resolves, and quietly loses the two spaces that make a list read as a list -
        // exactly the shape of the continuation bug BundleContinuationTest was written for.
        for (final String lang : List.of("en", "de")) {
            final Properties properties = new Properties();
            try (InputStream stream = HelpFormatTest.class.getClassLoader()
                    .getResourceAsStream(ROOT + "/" + lang + ".properties")) {
                assertNotNull(stream, lang);
                properties.load(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
            }
            assertTrue(properties.getProperty("command.help.line").startsWith("  "),
                    "command.help.line lost its indent in " + lang + " - escape the leading spaces"
                            + " as \\ \\ , or every command in the list starts at column zero");
        }
    }

    @Test
    @DisplayName("a usage line and its explanation are separate keys, so either can be dropped")
    void theTwoHalvesAreSeparate() {
        // An operator who finds the explanations noisy can blank command.help.what and keep the
        // syntax. That only works while they are two keys and the adapter sends two messages.
        assertEquals(List.of("usage"), SUPPLIED.get("command.help.usage"));
        assertEquals(List.of("what"), SUPPLIED.get("command.help.what"));
    }

    private static List<String> placeholders(final String text) {
        final java.util.List<String> found = new java.util.ArrayList<>();
        final java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]*)}").matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }
}
