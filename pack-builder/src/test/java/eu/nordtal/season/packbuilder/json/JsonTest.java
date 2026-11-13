package eu.nordtal.season.packbuilder.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void aReadNumberIsWrittenAsItWasRead() {
        final String text = "{\"tints\":[{\"type\":\"minecraft:dye\",\"default\":-12012264}],"
                + "\"scale\":[0.5,1.0,0.625],\"big\":4294967296,\"tiny\":0.00001,\"none\":null,\"on\":true}";
        assertEquals(text, Json.write(parse(text)));
    }

    @Test
    void aWholeNumberIsReadAsAnIntegerAndAFractionAsADecimal() {
        final Map<?, ?> map = (Map<?, ?>) parse("{\"a\":-12012264,\"b\":1.0,\"c\":4294967296}");
        assertEquals(-12012264, map.get("a"));
        assertEquals(new BigDecimal("1.0"), map.get("b"));
        assertInstanceOf(Long.class, map.get("c"));
    }

    @Test
    void aComputedNumberIsWrittenToFourPlacesAndAWholeOneWithoutAPoint() {
        assertEquals("[0.3333,2,0,-0.5,0]", Json.write(List.of(1 / 3.0, 2.0, 0.00001, -0.5, -0.0)));
    }

    @Test
    void anObjectKeepsItsKeyOrderAndAStringIsEscaped() {
        final String text = "{\"z\":\"a\\\"b\\\\c\",\"a\":\"\\u0001\"}";
        assertEquals(text, Json.write(parse(text)));
    }

    private static Object parse(final String text) {
        return Json.parse(text.getBytes(StandardCharsets.UTF_8));
    }
}
