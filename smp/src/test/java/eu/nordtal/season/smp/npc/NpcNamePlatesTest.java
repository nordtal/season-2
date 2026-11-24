package eu.nordtal.season.smp.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.database.npc.Dialogue;
import eu.nordtal.season.database.npc.Dialogue.Step;
import eu.nordtal.season.database.npc.Dialogue.Words;
import eu.nordtal.season.database.npc.Npc;
import eu.nordtal.season.database.npc.Npc.PlateColour;
import eu.nordtal.season.database.npc.Npc.PlateIcon;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.packrendering.npc.NpcPlate;
import eu.nordtal.season.packrendering.ui.Symbol;
import eu.nordtal.season.papercommon.hud.PillHologram;
import eu.nordtal.season.papercommon.npc.NpcLooks;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class NpcNamePlatesTest {

    private static final UUID ENGLISH = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID GERMAN = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final Map<UUID, Locale> LANGUAGES = Map.of(ENGLISH, Locale.ENGLISH, GERMAN, Locale.GERMAN);

    private static final MessageRenderer RENDERER = MessageRenderer.of(
                    Messages.load("messages/database", Locale.ENGLISH, Locale.GERMAN))
            .reading(new AppliedDocuments(NpcNamePlatesTest.class.getClassLoader(), Set.of("smp")));

    @Test
    void eachViewerSeesTheNameInTheirLanguage() {
        final Function<UUID, PillHologram.Layers> plate = NpcNamePlates.plate(smith(), LANGUAGES::get, viewer -> false);

        final String english = plain(plate.apply(ENGLISH).ink());
        final String german = plain(plate.apply(GERMAN).ink());
        assertTrue(english.contains("Smith"), english);
        assertTrue(german.contains("Schmiedin"), german);
    }

    @Test
    void thePlateTakesTheColourAndIconChosenForTheNpc() {
        final Npc smith = smith().withLook(new Npc.Plate(PlateColour.RED, PlateIcon.PICKAXE), null);
        final PillHologram.Layers layers =
                NpcNamePlates.plate(smith, LANGUAGES::get, viewer -> false).apply(ENGLISH);

        final NpcPlate.Look look = new NpcPlate.Look(TextColor.color(PlateColour.RED.rgb()), Symbol.PICKAXE);
        assertEquals(NpcPlate.panel(look, "Smith", false), layers.panel());
        assertEquals(NpcPlate.ink(look, "Smith", false), layers.ink());
    }

    @Test
    void onlyAViewerNearbySeesTheSpeechBubble() {
        final Function<UUID, PillHologram.Layers> plate = NpcNamePlates.plate(smith(), LANGUAGES::get, GERMAN::equals);
        final NpcPlate.Look look = NpcLooks.plate(smith());

        assertEquals(NpcPlate.panel(look, "Smith", false), plate.apply(ENGLISH).panel());
        assertEquals(
                NpcPlate.panel(look, "Schmiedin", true), plate.apply(GERMAN).panel());
        assertEquals(NpcPlate.ink(look, "Schmiedin", true), plate.apply(GERMAN).ink());
    }

    @Test
    void speechBubbleAppearsWithinTwelveBlocks() {
        assertEquals(12.0, NpcNamePlates.NEAR);
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

    private static String plain(final Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
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
