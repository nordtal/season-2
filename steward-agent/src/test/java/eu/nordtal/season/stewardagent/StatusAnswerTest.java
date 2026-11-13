package eu.nordtal.season.stewardagent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Tests reading the protocol out of a server list ping's raw answer. */
class StatusAnswerTest {

    @Test
    void theProtocolIsReadFromTheJsonBehindThePacketHeader() {
        // The packet length, id and string length come first as bytes the ping script passes through.
        final String raw = "Ä\u0001\u0000Á\u0001{\"version\":{\"name\":\"Paper 26.3\",\"protocol\":777},"
                + "\"players\":{\"max\":20,\"online\":0},\"description\":{\"text\":\"{braces} in a motd\"}}";

        assertEquals(OptionalInt.of(777), StatusAnswer.protocol(raw));
    }

    @Test
    void aLengthPrefixThatIsTheByteOfABraceIsSkipped() {
        // 0x7b is the varint of a 123-byte packet, and also an opening brace.
        final String raw = "{\u0000y{\"version\":{\"name\":\"Velocity\",\"protocol\":777}}";

        assertEquals(OptionalInt.of(777), StatusAnswer.protocol(raw));
    }

    @Test
    void anAnswerWithoutAProtocolIsNoAnswer() {
        assertEquals(OptionalInt.empty(), StatusAnswer.protocol(""));
        assertEquals(OptionalInt.empty(), StatusAnswer.protocol("connection refused"));
        assertEquals(OptionalInt.empty(), StatusAnswer.protocol("{\"version\":{\"name\":\"x\"}}"));
        assertEquals(OptionalInt.empty(), StatusAnswer.protocol("{\"version\":"));
    }
}
