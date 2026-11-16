package eu.nordtal.season.database.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.npc.Dialogue.Answer;
import eu.nordtal.season.database.npc.Dialogue.Step;
import eu.nordtal.season.database.npc.Dialogue.Words;
import eu.nordtal.season.database.npc.Npc.Equipment;
import eu.nordtal.season.database.npc.Npc.Place;
import eu.nordtal.season.database.npc.Npc.Pose;
import eu.nordtal.season.database.npc.Npc.Slot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Stores NPCs and who skips them against PostgreSQL and the real migrations. */
class NpcStoreIntegrationTest {

    private static final Actor ADMIN = Actor.person(DiscordId.of("123456789012345678"));
    private static final UUID SPAWN = UUID.fromString("6f1c3c5e-6a43-4c1b-9a7e-2a5d8c0b3e41");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static Npc smith(final String greeting) {
        return new Npc(
                UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
                new Words("Smith", "Schmiedin"),
                new Place("nordtal", 1.5, 64, -2.5, 90),
                Pose.SIT,
                1.4f,
                Map.of(Slot.HAND, new Equipment("minecraft:iron_sword", "AAAA")),
                new Dialogue(
                        "hello",
                        List.of(
                                new Step(
                                        "hello",
                                        0,
                                        0,
                                        new Words(greeting, "Moin"),
                                        List.of(
                                                new Answer(new Words("Show me", "Zeig"), "menu"),
                                                new Answer(new Words("Bye", "Tschüss"), null)),
                                        null,
                                        null),
                                new Step("menu", 200, 0, new Words("Here.", "Hier."), List.of(), null, "objectives"))));
    }

    @Test
    void theSpawnFigureIsTheFirstNpcAndHandsThePlayerToTheObjectives() {
        final List<Npc> npcs = NpcStore.using(TestDatabase.fresh().dataSourceAs(DatabaseRole.SMP))
                .npcs();

        assertEquals(List.of(SPAWN), npcs.stream().map(Npc::id).toList());
        final Npc spawn = npcs.getFirst();
        assertEquals(new Place("nordtal", 3980, 84, 2194, -180), spawn.place());
        assertEquals(List.of(), spawn.dialogue().problems(Set.of("objectives")));
        assertTrue(spawn.dialogue().step("welcome").orElseThrow().handsOff());
    }

    @Test
    void stewardSavesAnNpcWholeAndSmpReadsTheSameNpcBack() {
        final TestDatabase database = TestDatabase.fresh();
        final NpcStore steward = NpcStore.using(database.dataSourceAs(DatabaseRole.STEWARD));
        steward.save(smith("Hi"), ADMIN);
        final Npc changed = new Npc(
                smith("Hello").id(),
                smith("Hello").name(),
                smith("Hello").place(),
                Pose.CROUCH,
                0.8f,
                Map.of(Slot.HEAD, new Equipment("minecraft:iron_helmet", "BBBB")),
                smith("Hello").dialogue());
        steward.save(changed, ADMIN);

        assertEquals(
                changed,
                NpcStore.using(database.dataSourceAs(DatabaseRole.SMP))
                        .npc(changed.id())
                        .orElseThrow());
    }

    @Test
    void aSaveKeepsTheDialogueAsAVersionOnlyWhenItChanged() {
        final NpcStore store = NpcStore.using(TestDatabase.fresh().dataSourceAs(DatabaseRole.STEWARD));
        store.save(smith("Hi"), ADMIN);
        store.save(smith("Hi"), Actor.STEWARD);
        store.save(smith("Hello"), Actor.STEWARD);

        final List<NpcStore.Version> versions = store.versions(smith("Hi").id());
        assertEquals(
                List.of(smith("Hello").dialogue(), smith("Hi").dialogue()),
                versions.stream().map(NpcStore.Version::dialogue).toList());
        assertEquals(
                List.of(Actor.STEWARD, ADMIN),
                versions.stream().map(NpcStore.Version::actor).toList());
    }

    @Test
    void aPlayerWhoSkipsGoesToTheMenuUntilTheNpcIsRemoved() {
        final TestDatabase database = TestDatabase.fresh();
        final NpcStore steward = NpcStore.using(database.dataSourceAs(DatabaseRole.STEWARD));
        final NpcStore smp = NpcStore.using(database.dataSourceAs(DatabaseRole.SMP));
        steward.save(smith("Hi"), ADMIN);
        smp.skip(smith("Hi").id(), PLAYER, "objectives");
        smp.skip(SPAWN, PLAYER, "objectives");
        smp.unskip(SPAWN, PLAYER);
        assertEquals(Map.of(smith("Hi").id(), "objectives"), smp.skips(PLAYER));

        steward.remove(smith("Hi").id(), ADMIN);

        assertEquals(Map.of(), smp.skips(PLAYER));
        assertEquals(List.of(SPAWN), smp.npcs().stream().map(Npc::id).toList());
    }
}
