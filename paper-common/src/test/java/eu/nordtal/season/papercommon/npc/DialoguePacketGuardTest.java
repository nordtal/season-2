package eu.nordtal.season.papercommon.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Messages;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Tests inbound packet guards and localized skip texts for NPC dialogues. */
class DialoguePacketGuardTest {

    @Test
    void blocksCreativeSlotWindowClickAndDropOrSwapDiggingPackets() {
        assertTrue(DialoguePacketGuard.shouldBlock(PacketType.Play.Client.CREATIVE_INVENTORY_ACTION, null));
        assertTrue(DialoguePacketGuard.shouldBlock(PacketType.Play.Client.CLICK_WINDOW, null));
        assertTrue(DialoguePacketGuard.shouldBlock(PacketType.Play.Client.PLAYER_DIGGING, DiggingAction.DROP_ITEM));
        assertTrue(
                DialoguePacketGuard.shouldBlock(PacketType.Play.Client.PLAYER_DIGGING, DiggingAction.DROP_ITEM_STACK));
        assertTrue(DialoguePacketGuard.shouldBlock(
                PacketType.Play.Client.PLAYER_DIGGING, DiggingAction.SWAP_ITEM_WITH_OFFHAND));

        assertFalse(
                DialoguePacketGuard.shouldBlock(PacketType.Play.Client.PLAYER_DIGGING, DiggingAction.START_DIGGING));
        assertFalse(DialoguePacketGuard.shouldBlock(PacketType.Play.Client.HELD_ITEM_CHANGE, null));
        assertFalse(DialoguePacketGuard.shouldBlock(PacketType.Play.Client.PLAYER_POSITION, null));
    }

    @Test
    void activatesAndDeactivatesPerPlayer() {
        final DialoguePacketGuard guard = new DialoguePacketGuard(id -> {});
        final UUID player = UUID.randomUUID();

        assertFalse(guard.isActive(player));
        guard.activate(player);
        assertTrue(guard.isActive(player));
        guard.deactivate(player);
        assertFalse(guard.isActive(player));
    }

    @Test
    void skipSwitchAndPostCloseNoticeRenderInEnglishAndGerman() {
        final MessageRenderer renderer = MessageRenderer.of(
                        Messages.load("messages/database", Locale.ENGLISH, Locale.GERMAN))
                .reading(new AppliedDocuments(DialoguePacketGuardTest.class.getClassLoader(), Set.of("paper-common")));

        final Labels en = DialogueTexts.labels(renderer, new NpcMenus()).apply(Locale.ENGLISH);
        final Labels de = DialogueTexts.labels(renderer, new NpcMenus()).apply(Locale.GERMAN);
        assertEquals("Don't show this dialog again", en.dontShowAgain());
        assertEquals("Diesen Dialog nicht mehr zeigen", de.dontShowAgain());

        final String noticeEn = PlainTextComponentSerializer.plainText()
                .serialize(DialogueTexts.skipNotice(renderer).apply(Locale.ENGLISH, "Runa"));
        final String noticeDe = PlainTextComponentSerializer.plainText()
                .serialize(DialogueTexts.skipNotice(renderer).apply(Locale.GERMAN, "Runa"));
        assertTrue(noticeEn.contains("Runa") && noticeEn.contains("Shift"), noticeEn);
        assertTrue(noticeDe.contains("Runa") && noticeDe.contains("Shift"), noticeDe);
    }
}
