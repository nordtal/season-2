package eu.nordtal.season.discordbot.access.discord;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.discordbot.DocumentRenderer;
import eu.nordtal.season.discordbot.StoredDocumentsStandIn;
import eu.nordtal.season.messages.document.DocumentSpec;
import java.util.Locale;
import java.util.Map;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;
import org.junit.jupiter.api.Test;

/** Only the texts of the modal come from its document. */
class LinkFlowTest {

    private static final DocumentRenderer RENDERER = StoredDocumentsStandIn.renderer();

    @Test
    void theCodeModalIsTitledAndLabelledByItsTextsAroundTheFieldTheCodeBuilds() {
        final Modal modal = RENDERER.modal(
                LinkFlow.CODE,
                "link-modal",
                new DocumentSpec.None(),
                Map.of(
                        "code",
                        new DocumentRenderer.FilledInput(placeholder -> TextInput.create("code", TextInputStyle.SHORT)
                                .setPlaceholder(placeholder)
                                .build())),
                Locale.ENGLISH);
        final Label label = (Label) modal.getComponents().getFirst();

        assertAll(
                () -> assertEquals("Link your account", modal.getTitle()),
                () -> assertEquals("link-modal", modal.getId()),
                () -> assertEquals("Code", label.getLabel()),
                () -> assertEquals(
                        "The code from the login screen",
                        label.getChild().asTextInput().getPlaceHolder()));
    }
}
