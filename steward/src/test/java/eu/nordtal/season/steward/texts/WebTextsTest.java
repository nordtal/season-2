package eu.nordtal.season.steward.texts;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.AdminTexts;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.MessageSchema;
import eu.nordtal.season.steward.alert.AlertRouter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class WebTextsTest {

    private final Messages texts = WebTexts.load().messages();

    /** Tests that the title of a stored alert names a key of the admin bundle. */
    @Test
    void everyAdminKeyThePageShowsIsServed() {
        for (final MessageSchema.Entry entry : MessageSchema.entries(AdminTexts.class)) {
            if (entry.shown().contains(Display.STEWARD)) {
                assertTrue(texts.hasTranslation(Locales.DEFAULT, entry.key()), entry.key());
            }
        }
    }

    @Test
    void aRunAlertsTitleRendersForEveryKindAndStage() {
        for (final UpdateKind kind : UpdateKind.values()) {
            for (final UpdateReport.Stage stage : UpdateReport.Stage.values()) {
                final String shown = texts.format(Locales.DEFAULT, TEXTS.run().title(kind, stage));
                assertNotEquals("run.title", shown, kind + " " + stage);
                assertTrue(!shown.isBlank(), kind + " " + stage);
            }
        }
    }

    /** Tests that the current alerts page can render every alert, its title, its lines and what they name. */
    @Test
    void everyAlertActionRendersOnThePage() {
        final Set<String> shown = MessageSchema.entries(AdminTexts.class).stream()
                .filter(entry -> entry.shown().contains(Display.STEWARD))
                .map(MessageSchema.Entry::key)
                .collect(Collectors.toSet());
        for (final JournalAction action : JournalAction.values()) {
            if (AlertType.of(action) == null) {
                continue;
            }
            final Alert alert = AlertRouter.sample(action);
            final List<MessageRef> rendered = new ArrayList<>(alert.lines());
            rendered.add(alert.title());
            for (final MessageRef message : rendered) {
                for (final MessageRef reached : reached(message)) {
                    if (!shown.contains(reached.key())) {
                        fail(action + " needs " + reached.key() + ", which Steward does not show");
                    }
                }
                final String text = texts.format(Locales.DEFAULT, message);
                assertNotEquals(message.key(), text, action.name());
                assertTrue(!text.isBlank(), action.name());
            }
        }
    }

    /** Returns the message and every message among its arguments. */
    private static List<MessageRef> reached(final MessageRef message) {
        final List<MessageRef> all = new ArrayList<>(List.of(message));
        for (final Object arg : message.args().values()) {
            if (arg instanceof MessageRef nested) {
                all.addAll(reached(nested));
            } else if (arg instanceof List<?> list) {
                list.stream()
                        .filter(MessageRef.class::isInstance)
                        .forEach(nested -> all.addAll(reached((MessageRef) nested)));
            }
        }
        return all;
    }
}
