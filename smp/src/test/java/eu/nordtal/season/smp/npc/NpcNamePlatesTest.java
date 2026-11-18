package eu.nordtal.season.smp.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.database.npc.Dialogue;
import eu.nordtal.season.database.npc.Dialogue.Step;
import eu.nordtal.season.database.npc.Dialogue.Words;
import eu.nordtal.season.database.npc.Npc;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.papercommon.hud.PillHologram;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class NpcNamePlatesTest {

    private static final UUID ENGLISH = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID GERMAN = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final MessageRenderer RENDERER = MessageRenderer.of(
                    Messages.load("messages/database", Locale.ENGLISH, Locale.GERMAN))
            .reading(new AppliedDocuments(NpcNamePlatesTest.class.getClassLoader(), Set.of("smp")));

    @Test
    void eachViewerSeesTheNameInTheirLanguage() {
        final Function<UUID, PillHologram.Layers> plate =
                NpcNamePlates.plate(smith(), Map.of(ENGLISH, Locale.ENGLISH, GERMAN, Locale.GERMAN)::get, RENDERER);

        final String english = plain(plate.apply(ENGLISH));
        final String german = plain(plate.apply(GERMAN));
        assertTrue(english.contains("Smith"), english);
        assertTrue(german.contains("Schmiedin"), german);
        assertFalse(english.contains("<"), "the plate's markup is formatted, never drawn");
    }

    @Test
    void theObjectivesHintIsTranslated() {
        assertEquals("Open objectives", NpcTexts.objectivesHint(RENDERER, Locale.ENGLISH));
        assertEquals("Ziele öffnen", NpcTexts.objectivesHint(RENDERER, Locale.GERMAN));
    }

    @Test
    void theWheelHintIsTranslated() {
        assertEquals("Open wheel", NpcTexts.wheelHint(RENDERER, Locale.ENGLISH));
        assertEquals("Glücksrad öffnen", NpcTexts.wheelHint(RENDERER, Locale.GERMAN));
    }

    private static String plain(final PillHologram.Layers layers) {
        return PlainTextComponentSerializer.plainText().serialize(layers.ink());
    }

    private static Npc smith() {
        return new Npc(
                UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
                new Words("Smith", "Schmiedin"),
                new Npc.Place("nordtal", 1.5, 64, -2.5, 90),
                Npc.Pose.STAND,
                1.0f,
                Map.of(),
                new Dialogue(
                        "hello",
                        List.of(new Step(
                                "hello", 0, 0, new Words("Hi", "Moin"), List.of(), null, ObjectivesMenu.MENU))));
    }
}
