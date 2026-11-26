package eu.nordtal.season.papercommon.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.papercommon.npc.DialoguePacketGuard.ChatBuffer;
import eu.nordtal.season.papercommon.npc.DialoguePacketGuard.SlotMask;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Tests inbound and outbound packet guards, chat buffering and localized skip texts for NPC dialogues. */
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
    void mainHandUseItemConfirmsDialogueWhileOffHandDoesNot() {
        assertTrue(DialoguePacketGuard.isConfirmUseItem(PacketType.Play.Client.USE_ITEM, InteractionHand.MAIN_HAND));
        assertFalse(DialoguePacketGuard.isConfirmUseItem(PacketType.Play.Client.USE_ITEM, InteractionHand.OFF_HAND));
        assertFalse(DialoguePacketGuard.isConfirmUseItem(PacketType.Play.Client.ANIMATION, InteractionHand.MAIN_HAND));
    }

    @Test
    void masksHotbarOffHandAndVignetteSlotsWithoutTouchingOtherSlots() {
        assertEquals(SlotMask.HIDDEN_HAND, DialoguePacketGuard.windowSlotMask(ViewPackets.HOTBAR_SLOT + 4, 4, 3));
        assertEquals(SlotMask.EMPTY, DialoguePacketGuard.windowSlotMask(ViewPackets.HOTBAR_SLOT + 1, 4, 3));
        assertEquals(SlotMask.EMPTY, DialoguePacketGuard.windowSlotMask(ViewPackets.OFF_HAND_SLOT, 4, 3));
        assertEquals(SlotMask.VIGNETTE, DialoguePacketGuard.windowSlotMask(ViewPackets.HELMET_SLOT, 4, 3));
        assertEquals(SlotMask.KEEP, DialoguePacketGuard.windowSlotMask(ViewPackets.HELMET_SLOT, 4, 0));
        assertEquals(SlotMask.KEEP, DialoguePacketGuard.windowSlotMask(12, 4, 3));

        assertEquals(SlotMask.HIDDEN_HAND, DialoguePacketGuard.playerInventorySlotMask(4, 4, 2));
        assertEquals(SlotMask.EMPTY, DialoguePacketGuard.playerInventorySlotMask(0, 4, 2));
        assertEquals(
                SlotMask.EMPTY, DialoguePacketGuard.playerInventorySlotMask(ViewPackets.PLAYER_INV_OFF_HAND, 4, 2));
        assertEquals(
                SlotMask.VIGNETTE, DialoguePacketGuard.playerInventorySlotMask(ViewPackets.PLAYER_INV_HELMET, 4, 2));
        assertEquals(SlotMask.KEEP, DialoguePacketGuard.playerInventorySlotMask(ViewPackets.PLAYER_INV_HELMET, 4, 0));
        assertEquals(SlotMask.KEEP, DialoguePacketGuard.playerInventorySlotMask(20, 4, 2));
    }

    @Test
    void suppressesNonOverlayChatAndBuffersMessagesInOrderUpToLimit() {
        assertTrue(DialoguePacketGuard.shouldSuppressSystemChat(false));
        assertFalse(DialoguePacketGuard.shouldSuppressSystemChat(true));

        final ChatBuffer<String> buffer = new ChatBuffer<>(3);
        buffer.add("first");
        buffer.add("second");
        buffer.add("third");
        buffer.add("fourth");

        assertEquals(List.of("second", "third", "fourth"), buffer.drain());
        assertTrue(buffer.drain().isEmpty());
    }

    @Test
    void activatesAndDeactivatesPerPlayer() {
        final DialoguePacketGuard guard = new DialoguePacketGuard(id -> {}, id -> {});
        final UUID player = UUID.randomUUID();

        assertFalse(guard.isActive(player));
        guard.activate(player, 0, null);
        assertTrue(guard.isActive(player));
        assertTrue(guard.deactivate(player).isEmpty());
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
