package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.npc.Npc;
import eu.nordtal.season.database.npc.NpcStore;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

class NpcPlateUpgradeIntegrationTest {

    private static final UUID RUNA = UUID.fromString("6f1c3c5e-6a43-4c1b-9a7e-2a5d8c0b3e41");

    @Test
    void anNpcWithAnotherPlateStyleBecomesAPillAndKeepsItsColourAndIcon() {
        final var database = TestDatabase.empty();
        final var placeholders = DatabaseRole.placeholders(DatabaseRole.PREFIX);
        database.migrate(placeholders, "140");
        final var jdbi = Jdbi.create(database.dataSource());
        jdbi.useHandle(handle -> handle.execute(
                "UPDATE npc SET plate_style = 'BADGE', plate_colour = 'RED', plate_icon = 'HEART' WHERE id = ?", RUNA));

        database.migrate(placeholders, "141");

        assertEquals(
                "PILL",
                jdbi.withHandle(handle -> handle.createQuery("SELECT plate_style FROM npc WHERE id = :id")
                        .bind("id", RUNA)
                        .mapTo(String.class)
                        .one()));
        assertEquals(
                new Npc.Plate(Npc.PlateColour.RED, Npc.PlateIcon.HEART),
                NpcStore.using(database.dataSource()).npc(RUNA).orElseThrow().plate());
    }
}
