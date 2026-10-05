package eu.nordtal.season.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.DatabaseText;
import org.junit.jupiter.api.Test;

/** A size reads the same in a log line and in a report, and only the report's words can change. */
class ByteSizeTest {

    @Test
    void belowAKibibyteItIsWholeBytes() {
        assertEquals("512 B", ByteSize.of(512).toString());
        assertEquals("1023 B", ByteSize.of(1023).toString());
    }

    @Test
    void aboveItIsOneDecimalInTheLargestUnitThatIsNotBelowOne() {
        assertEquals("2.0 KiB", ByteSize.of(2048).toString());
        assertEquals("1.2 MiB", ByteSize.of(1_234_567).toString());
        assertEquals("1.0 GiB", ByteSize.of(1L << 30).toString());
        assertEquals("2048.0 TiB", ByteSize.of(1L << 51).toString(), "there is no unit past the last");
    }

    @Test
    void aReportTellsTheAmountAsANumberAndTheUnitAsAChoice() {
        assertEquals("512 B", DatabaseText.english(ByteSize.of(512).message()));
        assertEquals("2 KiB", DatabaseText.english(ByteSize.of(2048).message()));
        assertEquals("7.3 MiB", DatabaseText.english(ByteSize.of(7_654_321).message()));
        assertEquals("1 GiB", DatabaseText.english(ByteSize.of(1L << 30).message()));
    }
}
