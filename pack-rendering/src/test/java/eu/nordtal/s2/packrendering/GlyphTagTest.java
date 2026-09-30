package eu.nordtal.s2.packrendering;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

class GlyphTagTest {

    private static final MessageRenderer RENDER = MessageRenderer.of(Messages.load("messages/glyph", Locale.ENGLISH));

    @Test
    void aGlyphTagNamesAGlyphAndDrawsItInTheDefaultFont() {
        final Component rendered = RENDER.get(Locale.ENGLISH, "glyph");
        final Component glyph = rendered.children().getFirst();

        assertEquals(Glyphs.TAG_ADMIN, ((TextComponent) glyph).content());
        assertEquals(
                Key.key("minecraft", "default"),
                glyph.font(),
                "the glyph named no font, so a surrounding font would draw something else there");
    }
}
