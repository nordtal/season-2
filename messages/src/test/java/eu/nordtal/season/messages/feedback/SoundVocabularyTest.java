package eu.nordtal.season.messages.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** {@link Feedback} is a closed set of names, so the config file of a module is the only source of a sound.
 *
 * {@code :architecture} checks that a call site picks a category and never a sound.
 */
class SoundVocabularyTest {

    @Test
    void feedbackCarriesNothingButItsConstants() {
        assertEquals(
                12,
                Feedback.values().length,
                "ten categories, of which open/close is two constants, plus STAGING and RECLAIMED."
                        + " A THIRTEENTH IS A DECISION FOR THE OWNER - a"
                        + " vocabulary that grows to fit each new call site is not a vocabulary");
        // values/valueOf are the API of the enum, and $values is the array holder of javac, not always synthetic.
        assertEquals(
                List.of(),
                Stream.of(Feedback.class.getDeclaredMethods())
                        .map(java.lang.reflect.Method::getName)
                        .filter(name -> !name.equals("values") && !name.equals("valueOf") && !name.startsWith("$"))
                        .sorted()
                        .toList(),
                "Feedback is a name and nothing else - what a category sounds like belongs in a"
                        + " module's config.yml, parsed into FeedbackSounds");
        assertEquals(
                List.of(),
                Stream.of(Feedback.class.getDeclaredFields())
                        .filter(field -> !field.isEnumConstant() && !field.isSynthetic())
                        .map(java.lang.reflect.Field::getName)
                        .sorted()
                        .toList(),
                "a field on Feedback is a sound name waiting to be hardcoded");
    }
}
