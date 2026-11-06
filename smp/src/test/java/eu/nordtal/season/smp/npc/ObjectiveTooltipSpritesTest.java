package eu.nordtal.season.smp.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messages.context.MilestoneContext;
import eu.nordtal.season.messages.value.GameContent;
import eu.nordtal.season.packrendering.menu.ItemSprites;
import eu.nordtal.season.papercommon.game.GameKeys;
import eu.nordtal.season.smp.menu.PanelWalk;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** An objective's tooltip draws each wanted item's texture before its name, and the name alone where none draws. */
class ObjectiveTooltipSpritesTest {

    private static final ObjectiveGui.Card CARD =
            new ObjectiveGui.Card("Mine 64 diamonds", false, true, false, "████░░░░", 40, 64, false, 0, 0, 0);

    /** A server's registries name a vanilla item, so the test builds the content that {@link HandInGui#wanted} does. */
    private static final HandInGui.Wanted DIAMOND =
            new HandInGui.Wanted(new GameContent("item.minecraft.diamond", "Diamond")
                    .drawn(ItemSprites.of("minecraft:diamond").orElseThrow()));

    private static final HandInGui.Wanted OAK_LOG =
            new HandInGui.Wanted(new GameContent("block.minecraft.oak_log", "Oak Log")
                    .drawn(ItemSprites.of("minecraft:oak_log").orElseThrow()));

    @ParameterizedTest
    @ValueSource(strings = {"en", "de"})
    void aWantedItemsLineDrawsItsSpriteBeforeItsTranslatedName(final String language) {
        final List<Component> lore = lore(Locale.forLanguageTag(language), List.of(DIAMOND, OAK_LOG));

        assertEquals(
                JsonParser.parseString(
                        "{\"color\":\"white\",\"italic\":false,\"shadow_color\":0,\"atlas\":\"minecraft:items\",\"sprite\":\"minecraft:item/diamond\"}"),
                JsonParser.parseString(sprite(lore.get(lore.size() - 2))));
        // The block atlas is the client's default, so the JSON leaves it out.
        assertEquals(
                JsonParser.parseString(
                        "{\"color\":\"white\",\"italic\":false,\"shadow_color\":0,\"sprite\":\"minecraft:block/oak_log\"}"),
                JsonParser.parseString(sprite(lore.getLast())));
        final String line = GsonComponentSerializer.gson().serialize(lore.getLast());
        assertTrue(line.contains("\"translate\":\"block.minecraft.oak_log\""), line);
    }

    @Test
    void anItemWithoutASpriteShowsItsNameAloneAsBefore() {
        final GameDocument.Drawn drawn = drawn(Locale.ENGLISH, HandInGui.wanted(List.of("nordtal:relic")));

        final String line =
                GsonComponentSerializer.gson().serialize(drawn.lore().getLast());
        assertFalse(line.contains("\"sprite\""), line);
        assertEquals("nordtal:relic", PanelWalk.lines(drawn).getLast());
    }

    /** A track names its items as Bukkit names or namespaced keys, and both find the item's sprite. */
    @ParameterizedTest
    @ValueSource(strings = {"DIAMOND", "minecraft:diamond", "OAK_LOG"})
    void aTracksItemNameFindsItsSprite(final String name) {
        assertTrue(ItemSprites.of(GameKeys.key(name)).isPresent(), name);
    }

    private static List<Component> lore(final Locale locale, final List<HandInGui.Wanted> wanted) {
        return drawn(locale, wanted).lore();
    }

    private static GameDocument.Drawn drawn(final Locale locale, final List<HandInGui.Wanted> wanted) {
        final GameDocument document = PanelWalk.document(
                ObjectiveGui.DOCUMENT,
                locale,
                new ObjectiveGui.Window(new MilestoneContext("The Nether"), 1, 4, 3, 10, false));
        return document.item("objective", CARD, Map.of("wanted", wanted));
    }

    /** The JSON of the first object component in {@code line}, which the client draws as the sprite. */
    private static String sprite(final Component line) {
        return find(line)
                .map(GsonComponentSerializer.gson()::serialize)
                .orElseThrow(() -> new AssertionError(
                        "no sprite in " + GsonComponentSerializer.gson().serialize(line)));
    }

    private static Optional<Component> find(final Component component) {
        if (component instanceof ObjectComponent) {
            return Optional.of(component);
        }
        return component.children().stream()
                .map(ObjectiveTooltipSpritesTest::find)
                .flatMap(Optional::stream)
                .findFirst();
    }
}
