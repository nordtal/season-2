package eu.nordtal.season.discordbot;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.junit.jupiter.api.Test;

/** The edit that turns a remembered embed message into Components V2. */
class ManagedMessageTest {

    @Test
    void theEditSetsComponentsV2EmptiesTheEmbedsAndReplacesTheImages() {
        try (MessageEditData edit = ManagedMessage.edit(
                new LayoutRenderer.Drawn(List.of(TextDisplay.of("Access")), List.of("link.png"), List.of()))) {
            final DataObject sent = edit.toData();

            assertAll(
                    () -> assertEquals(
                            Message.MessageFlag.IS_COMPONENTS_V2.getValue(),
                            sent.getInt("flags") & Message.MessageFlag.IS_COMPONENTS_V2.getValue()),
                    () -> assertTrue(
                            sent.isNull("content") || sent.getString("content").isEmpty(), sent::toString),
                    () -> assertEquals(0, sent.getArray("embeds").length()),
                    () -> assertEquals(
                            "link.png",
                            sent.getArray("attachments").getObject(0).getString("filename")));
        }
    }
}
