package eu.nordtal.season.steward.texts;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.AdminTexts;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.MessageSchema;
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
}
