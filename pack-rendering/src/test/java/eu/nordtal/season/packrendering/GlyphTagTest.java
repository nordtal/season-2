package eu.nordtal.season.packrendering;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Messages;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

class GlyphTagTest {

    private static final MessageRenderer RENDER = MessageRenderer.of(Messages.load("messages/glyph", Locale.ENGLISH));

    @Test
    void aGlyphTagNamesAGlyphAndDrawsItInTheDefaultFont() {
        final Component rendered = RENDER.format(Locale.ENGLISH, new MessageRef("glyph", Map.of()));
        final Component glyph = rendered.children().getFirst();

        assertEquals(Glyphs.TAG_ADMIN, ((TextComponent) glyph).content());
        assertEquals(
                Key.key("minecraft", "default"),
                glyph.font(),
                "the glyph named no font, so a surrounding font would draw something else there");
    }
}
