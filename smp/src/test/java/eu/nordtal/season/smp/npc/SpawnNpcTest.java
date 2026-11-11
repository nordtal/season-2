package eu.nordtal.season.smp.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.packrendering.ui.Pill;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class SpawnNpcTest {

    private static final UUID ENGLISH = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID GERMAN = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void eachViewerSeesTheNameTranslatedIntoTheirLanguage() {
        final MessageRenderer renderer = MessageRenderer.of(Messages.load("messages/database", Locale.ENGLISH))
                .reading(new AppliedDocuments(SpawnNpcTest.class.getClassLoader(), Set.of("smp")));
        final Function<UUID, Component> name =
                SpawnNpc.name(Map.of(ENGLISH, Locale.ENGLISH, GERMAN, Locale.GERMAN)::get, renderer);

        assertEquals(Pill.of(renderer.plainLine(Locale.ENGLISH, Sent.of(SpawnNpc.NAME))), name.apply(ENGLISH));
        assertEquals(Pill.of(renderer.plainLine(Locale.GERMAN, Sent.of(SpawnNpc.NAME))), name.apply(GERMAN));
    }
}
