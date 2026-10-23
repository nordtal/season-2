package eu.nordtal.season.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.season.database.inbox.StewardRequest;
import java.util.Arrays;
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
                case RESTORE -> new StewardRequest.Restore("db-20261002T030000Z.dump");
                case REMOVE_PLUGIN -> new StewardRequest.RemovePlugin("chunky");
                case INSTALL -> new StewardRequest.Install();
                default -> kind.request();
            };
            assertEquals(kind, UpdateKind.of(request));
        }
    }

    @Test
    void nobodyAsksForAStartUpInstall() {
        assertThrows(IllegalArgumentException.class, () -> UpdateKind.INSTALL.request());
        assertFalse(UpdateKind.INSTALL.stopsServers(), "it fills volumes before any server runs");
    }
}
