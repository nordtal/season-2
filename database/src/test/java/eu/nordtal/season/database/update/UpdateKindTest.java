package eu.nordtal.season.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.season.database.inbox.StewardRequest;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The kinds of a run and the kinds of the run inbox are one list. */
class UpdateKindTest {

    @Test
    void everyKindIsARequestOfStewardsInboxAndBack() {
        assertEquals(
                StewardRequest.TABLE.kinds(),
                Arrays.stream(UpdateKind.values()).map(Enum::name).toList());
        for (final UpdateKind kind : UpdateKind.values()) {
            final StewardRequest request = switch (kind) {
                case RESTORE -> new StewardRequest.Restore(List.of(), "db-20261002T030000Z.dump");
                case REMOVE_PLUGIN -> new StewardRequest.RemovePlugin(List.of("smp"), "chunky");
                case INSTALL -> new StewardRequest.Install(List.of("smp"));
                default -> kind.request(List.of("smp"));
            };
            assertEquals(kind, UpdateKind.of(request));
        }
    }

    @Test
    void nobodyAsksForAStartUpInstall() {
        assertThrows(IllegalArgumentException.class, () -> UpdateKind.INSTALL.request(List.of()));
        assertFalse(UpdateKind.INSTALL.stopsServers(), "it fills volumes before any server runs");
    }

    @Test
    void aBackupRunsPageIsUnderBackupsAndEveryOtherUnderUpdates() {
        assertEquals("/operations/backups/249", UpdateKind.BACKUP.page(249));
        assertEquals("/operations/updates/7", UpdateKind.RESTORE.page(7));
    }
}
