package eu.nordtal.s2.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.database.inbox.WorkerRequest;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The kinds of a run and the kinds of the worker's inbox are one list. */
class UpdateKindTest {

    @Test
    void everyKindIsARequestOfTheWorkersInboxAndBack() {
        assertEquals(
                WorkerRequest.TABLE.kinds(),
                Arrays.stream(UpdateKind.values()).map(Enum::name).toList());
        for (final UpdateKind kind : UpdateKind.values()) {
            assertEquals(kind, UpdateKind.of(kind.request(List.of("smp"))));
        }
    }
}
