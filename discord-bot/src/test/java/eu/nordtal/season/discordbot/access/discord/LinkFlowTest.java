package eu.nordtal.season.discordbot.access.discord;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.discordbot.DiscordRenderer;
import eu.nordtal.season.discordbot.LayoutRenderer;
import eu.nordtal.season.discordbot.StoredLayoutsStandIn;
import eu.nordtal.season.messages.Messages;
import java.util.Locale;
import java.util.Map;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;
import org.junit.jupiter.api.Test;

/** The link message's modal, drawn from its layout: only its texts come from the bundle. */
class LinkFlowTest {

    private static final LayoutRenderer RENDERER = new LayoutRenderer(
            DiscordRenderer.of(Messages.load("messages/access", Locale.ENGLISH)), StoredLayoutsStandIn.none());

    @Test
    void theCodeModalIsTitledAndLabelledByItsTextsAroundTheFieldTheCodeBuilds() {
        final Modal modal = RENDERER.modal(
                LinkFlow.CODE,
                "link-modal",
                new LayoutRenderer.Filling(
                        Map.of(),
                        Map.of(
                                "code",
                                new LayoutRenderer.FilledInput(
                                        placeholder -> TextInput.create("code", TextInputStyle.SHORT)
                                                .setPlaceholder(placeholder)
                                                .build()))),
                Locale.ENGLISH);
        final Label label = (Label) modal.getComponents().getFirst();

        assertAll(
                () -> assertEquals("Link your account", modal.getTitle()),
                () -> assertEquals("link-modal", modal.getId()),
                () -> assertEquals("Code", label.getLabel()),
                () -> assertEquals(
                        "The code shown on the login screen",
                        label.getChild().asTextInput().getPlaceHolder()));
    }
}
