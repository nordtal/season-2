package eu.nordtal.season.papercommon.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class GameDataExportTest {

    @Test
    void customEnchantmentDescriptionFallbackResolvesToReadableEnglishName() {
        final Component description = Component.translatable("enchantment.nordtal.tunnel_miner", "Tunnel Miner");
        assertEquals(
                "Tunnel Miner",
                GameDataExport.descriptionText(
                        description, "enchantment.nordtal.tunnel_miner", PlainTextComponentSerializer.plainText()));
    }

    @Test
    void untranslatedKeyWithoutFallbackReturnsNull() {
        final Component bare = Component.translatable("enchantment.nordtal.unknown");
        assertNull(GameDataExport.descriptionText(
                bare, "enchantment.nordtal.unknown", PlainTextComponentSerializer.plainText()));
    }
}
