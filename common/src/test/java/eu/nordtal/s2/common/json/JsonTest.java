package eu.nordtal.s2.common.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class JsonTest {

    enum Stage {
        DONE
    }

    record Line(String service, @Nullable String detail, List<String> notes, Stage stage) {}

    @Test
    void aRecordRoundTripsWithItsEnumByName() {
        final Line line = new Line("smp", "<b>ok</b>", List.of("a"), Stage.DONE);

        final String text = Json.encode(line);

        assertEquals("{\"service\":\"smp\",\"detail\":\"<b>ok</b>\",\"notes\":[\"a\"],\"stage\":\"DONE\"}", text);
        assertEquals(line, Json.decode(text, Line.class));
    }

    @Test
    void aNullComponentIsLeftOutAndComesBackNull() {
        final String text = Json.encode(new Line("smp", null, List.of(), Stage.DONE));

        assertEquals("{\"service\":\"smp\",\"notes\":[],\"stage\":\"DONE\"}", text);
        assertEquals(null, Json.decode(text, Line.class).detail());
    }

    record Timed(Instant at, Duration took) {}

    @Test
    void anInstantAndADurationTravelAsTheirIsoText() {
        final Timed timed = new Timed(Instant.parse("2026-10-02T01:02:03Z"), Duration.ofSeconds(90));

        final String text = Json.encode(timed);

        assertEquals("{\"at\":\"2026-10-02T01:02:03Z\",\"took\":\"PT1M30S\"}", text);
        assertEquals(timed, Json.decode(text, Timed.class));
    }
}
