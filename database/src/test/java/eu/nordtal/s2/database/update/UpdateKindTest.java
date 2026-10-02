package eu.nordtal.s2.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.database.inbox.StewardRequest;
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
                default -> kind.request(List.of("smp"));
            };
            assertEquals(kind, UpdateKind.of(request));
        }
    }
}
