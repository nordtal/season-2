package eu.nordtal.season.steward.texts;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.messages.MessageOverride;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.PackagedTexts;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every text of Steward and of the admin bundle that names the game mode draws the game mode term. */
class GameModeTermTest {

    private static final String TERM = "terms.game-mode";

    private static final String RENAMED = "play style";

    /** Each text by its key, with the values that choose its branch about the game mode. */
    private static final List<Map.Entry<String, Map<String, String>>> TEXTS = List.of(
            Map.entry("journal.switch-mode", Map.of()),
            Map.entry("journal.switch-mode-because", Map.of()),
            Map.entry("journal.request-run", Map.of("kind", "switch-mode", "count", "0")),
            Map.entry("journal.cancel-run", Map.of("kind", "switch-mode")),
            Map.entry("run.kind", Map.of("kind", "switch-mode")),
            Map.entry("run.title", Map.of("kind", "switch-mode", "stage", "countdown")),
            Map.entry("run.title", Map.of("kind", "switch-mode", "stage", "stopping")),
            Map.entry("run.title", Map.of("kind", "switch-mode", "stage", "backing-up")),
            Map.entry("run.title", Map.of("kind", "switch-mode", "stage", "installing")),
            Map.entry("run.title", Map.of("kind", "switch-mode", "stage", "starting")),
            Map.entry("run.title", Map.of("kind", "switch-mode", "stage", "verifying")),
            Map.entry("run.title", Map.of("kind", "switch-mode", "stage", "failed")),
            Map.entry("steward.service.by-hand", Map.of()),
            Map.entry("steward.answer.switch-open", Map.of()),
            Map.entry("steward.operations.ask-warning", Map.of("kind", "switch-mode")),
            Map.entry("steward.service-page.resting-first", Map.of()),
            Map.entry("steward.router.until-runs", Map.of()),
            Map.entry("steward.router.start-tip", Map.of()),
            Map.entry("steward.router.stop-tip", Map.of()),
            Map.entry("steward.router.waiting-switch", Map.of()),
            Map.entry("steward.router.done", Map.of("change", "switch")));

    @Test
    void aRenamedGameModeTermReachesEveryTextThatNamesTheGameMode() {
        final Messages messages = WebTexts.load().messages();
        messages.override(List.of(new MessageOverride(
                Messages.TERMS,
                TERM,
                "en",
                0,
                RENAMED,
                PackagedTexts.hash(messages.packaged(Messages.TERMS, TERM, Locales.DEFAULT)))));

        for (final Map.Entry<String, Map<String, String>> text : TEXTS) {
            final String shown = messages.format(Locales.DEFAULT, messages.example(text.getKey(), text.getValue()));
            assertTrue(shown.contains(RENAMED), text.getKey() + " " + text.getValue() + ": " + shown);
        }
    }
}
