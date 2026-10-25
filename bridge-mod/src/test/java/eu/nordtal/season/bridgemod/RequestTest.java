package eu.nordtal.season.bridgemod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RequestTest {

    private static Request of(final String json) {
        return Request.of(JsonParser.parseString(json).getAsJsonObject());
    }

    private static String refusal(final String json) {
        return assertThrows(IllegalArgumentException.class, () -> of(json)).getMessage();
    }

    @Test
    void everyOpReadsItsFieldsAndItsDefaults() {
        assertEquals(new Request.Reload(), of("{\"op\":\"reload\"}"));
        assertEquals(
                new Request.Chat(JsonParser.parseString("{\"text\":\"a\"}")),
                of("{\"op\":\"chat\",\"text\":{\"text\":\"a\"}}"));
        assertEquals(
                new Request.Actionbar(JsonParser.parseString("\"a\"")), of("{\"op\":\"actionbar\",\"text\":\"a\"}"));
        assertEquals(new Request.Title(JsonParser.parseString("\"a\""), null), of("{\"op\":\"title\",\"text\":\"a\"}"));
        assertEquals(
                new Request.BossBar(JsonParser.parseString("\"a\""), "white", "progress", 1),
                of("{\"op\":\"bossbar\",\"text\":\"a\"}"));
        assertEquals(new Request.BossBar(null, "red", "notched_6", 0.5f), of("""
                {"op":"bossbar","color":"RED","overlay":"notched_6","progress":0.5}"""));
        assertEquals(new Request.Chest(JsonParser.parseString("\"a\""), 3), of("{\"op\":\"chest\",\"text\":\"a\"}"));
        assertEquals(
                new Request.Give("minecraft:paper[item_model=\"nordtal:x\"]", 1),
                of("{\"op\":\"give\",\"item\":\"minecraft:paper[item_model=\\\"nordtal:x\\\"]\"}"));
        assertEquals(
                new Request.Block("minecraft:stone", null), of("{\"op\":\"block\",\"block\":\"minecraft:stone\"}"));
        assertEquals(
                new Request.Block("minecraft:stone", new Request.Position(1, -2, 3)),
                of("{\"op\":\"block\",\"block\":\"minecraft:stone\",\"pos\":[1,-2,3]}"));
        assertEquals(new Request.Clear(), of("{\"op\":\"clear\"}"));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "{}|A request needs 'op' as a string.",
                "{'op':1}|A request needs 'op' as a string.",
                "{'op':'fly'}|The bridge knows no op 'fly'.",
                "{'op':'chat'}|chat needs 'text'.",
                "{'op':'chat','text':null}|chat needs 'text'.",
                "{'op':'give','item':3}|give needs 'item' as a string.",
                "{'op':'give','item':'a','count':100}|give needs 'count' from 1 to 99.",
                "{'op':'chest','text':'a','rows':7}|chest needs 'rows' from 1 to 6.",
                "{'op':'bossbar','progress':'full'}|bossbar needs 'progress' as a number.",
                "{'op':'bossbar','progress':2}|bossbar needs 'progress' from 0 to 1.",
                "{'op':'block','block':'a','pos':[1,2]}|block needs 'pos' as [x, y, z].",
            })
    void aWrongRequestSaysWhatIsWrong(final String json, final String message) {
        assertEquals(message, refusal(json.replace('\'', '"')));
    }
}
