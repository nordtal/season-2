package eu.nordtal.season.smp.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.papercommon.hud.PillHologram;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class SpawnNpcTest {

    private static final UUID ENGLISH = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID GERMAN = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void eachViewerSeesTheNameTranslatedIntoTheirLanguage() {
        final MessageRenderer renderer = MessageRenderer.of(Messages.load("messages/database", Locale.ENGLISH))
                .reading(new AppliedDocuments(SpawnNpcTest.class.getClassLoader(), Set.of("smp")));
        final Function<UUID, PillHologram.Layers> name =
                SpawnNpc.name(Map.of(ENGLISH, Locale.ENGLISH, GERMAN, Locale.GERMAN)::get, renderer);

        assertEquals(pill(renderer, Locale.ENGLISH), name.apply(ENGLISH));
        assertEquals(pill(renderer, Locale.GERMAN), name.apply(GERMAN));
        assertFalse(
                PlainTextComponentSerializer.plainText()
                        .serialize(name.apply(ENGLISH).ink())
                        .contains("<"),
                "the name's markup is formatted, never drawn");
    }

    private static PillHologram.Layers pill(final MessageRenderer renderer, final Locale locale) {
        return PillHologram.layers(
                null,
                PillHologram.runs(
                        renderer.format(locale, Sent.of(SpawnNpc.NAME)),
                        renderer.raw().environment().palette()));
    }
}
