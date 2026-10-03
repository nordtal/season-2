package eu.nordtal.s2.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.messages.context.MessageEnvironment;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Layers an admin's overrides, rows of the database, over the packaged bundle.
 *
 * The layer is per key, so a key no row names keeps following the jar.
 */
class MessageOverridesTest {

    private static final Locale GERMAN = Locale.GERMAN;

    @Test
    void anOverrideWinsOverThePackagedBundle() {
        final Messages messages = load();
        messages.override(List.of(row("greeting", "de", 0, "Moin {name}!")));

        assertEquals("Moin Alex!", messages.format(GERMAN, "greeting", "name", "Alex"));
    }

    @Test
    void aKeyNoRowNamesStillComesFromTheJar() {
        final Messages messages = load();
        messages.override(List.of(row("greeting", "de", 0, "Moin {name}!")));

        assertEquals(
                "Keine Parameter hier.",
                messages.get(GERMAN, "plain"),
                "the layer is per key: a row naming one line must not blank out the rest,"
                        + " or every message added by a later release reaches the player as its key");
        assertEquals("This key exists in English only.", messages.get(GERMAN, "only-english"));
    }

    @Test
    void germanMayOverrideAKeyOnlyEnglishDeclares() {
        final Messages messages = load();
        messages.override(List.of(row("only-english", "de", 0, "Nur hier.")));

        assertEquals("Nur hier.", messages.get(GERMAN, "only-english"));
    }

    @Test
    void theNextRowsReplaceTheLastSoADeletedOverrideFallsBackToTheJar() {
        final Messages messages = load();
        messages.override(List.of(row("greeting", "de", 0, "Moin {name}!")));
        messages.override(List.of());

        assertEquals("Hallo Alex!", messages.format(GERMAN, "greeting", "name", "Alex"));
    }

    @Test
    void aRowForAKeyNoBundleDeclaresIsLeftOut() {
        final Messages messages = load();
        messages.override(List.of(row("greting", "de", 0, "Moin!")));

        assertEquals("greting", messages.get(GERMAN, "greting"), "a typo in a row never invents a key");
    }

    @Test
    void aKeyWithVariantsAnswersEachOfThemAndNothingElse() {
        final Messages messages = load();
        final Set<String> seen = new HashSet<>();
        for (int draw = 0; draw < 200; draw++) {
            seen.add(messages.get(Locale.ENGLISH, "cheer"));
        }

        assertEquals(Set.of("Hooray!", "Yay!"), seen);
    }

    @Test
    void anOverridesVariantsReplaceThePackagedOnesTogether() {
        final Messages messages = load();
        messages.override(List.of(row("cheer", "en", 0, "Huzzah!")));
        final Set<String> seen = new HashSet<>();
        for (int draw = 0; draw < 50; draw++) {
            seen.add(messages.get(Locale.ENGLISH, "cheer"));
        }

        assertEquals(Set.of("Huzzah!"), seen, "one override of a key with two variants leaves it one text");
    }

    @Test
    void anOverrideStoredUnderAFormerNameFollowsTheKey() {
        final Messages messages = load();
        messages.override(List.of(new MessageOverride("old", "welcome", "de", 0, "Servus {name}!", null)));

        assertEquals("Servus Alex!", messages.format(GERMAN, "greeting", "name", "Alex"));
        assertTrue(messages.bundles().contains("old"), "the rows of the bundle a key moved out of are read too");
    }

    @Test
    void anOverrideUnderTheCurrentNameWinsOverOneUnderAFormerName() {
        final Messages messages = load();
        messages.override(List.of(row("hello", "de", 0, "Alt {name}"), row("greeting", "de", 0, "Neu {name}")));

        assertEquals("Neu Alex", messages.format(GERMAN, "greeting", "name", "Alex"));
    }

    @Test
    void theOverridesComeAlongIntoAProcesssOwnBundles() {
        final Messages messages = load();
        messages.override(List.of(row("plain", "de", 0, "Schlicht.")));

        assertEquals("Schlicht.", messages.within(MessageEnvironment.NONE).get(GERMAN, "plain"));
    }

    @Test
    void aRootOutsideTheMessagesDirectoryIsRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Messages.load(MessageOverridesTest.class.getClassLoader(), "test", GERMAN));
    }

    private static MessageOverride row(final String key, final String language, final int variant, final String text) {
        return new MessageOverride("test", key, language, variant, text, null);
    }

    private static Messages load() {
        return Messages.load(MessageOverridesTest.class.getClassLoader(), "messages/test", GERMAN);
    }
}
