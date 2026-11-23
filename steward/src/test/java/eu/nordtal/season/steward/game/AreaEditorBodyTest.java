package eu.nordtal.season.steward.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.season.database.inbox.AreaShape;
import eu.nordtal.season.database.inbox.SmpRequest;
import io.javalin.http.BadRequestResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class AreaEditorBodyTest {

    @Test
    void readsTheWorldTheHeightsAndTheShapesOfAnEdit() {
        final CommandApi.EditAreas edit = CommandApi.editAreasOf("""
                {"world":"nordtal","heights":false,"shapes":[
                  {"name":"Spawn","corners":[{"x":1,"z":2},{"x":3,"z":4},{"x":5,"z":0}],"bottom":0,"top":0}]}""");

        assertEquals(
                new CommandApi.EditAreas(
                        "nordtal",
                        false,
                        List.of(new AreaShape(
                                "Spawn",
                                List.of(
                                        new AreaShape.Corner(1, 2),
                                        new AreaShape.Corner(3, 4),
                                        new AreaShape.Corner(5, 0)),
                                0,
                                0))),
                edit);
    }

    @Test
    void refusesAnEditWithoutAWorldOrWithAShapeWithoutCorners() {
        assertThrows(BadRequestResponse.class, () -> CommandApi.editAreasOf("{\"heights\":false,\"shapes\":[]}"));
        assertThrows(BadRequestResponse.class, () -> CommandApi.editAreasOf("{\"world\":\" \",\"shapes\":[]}"));
        assertThrows(
                BadRequestResponse.class,
                () -> CommandApi.editAreasOf("{\"world\":\"nordtal\",\"shapes\":[{\"name\":\"Spawn\"}]}"));
        assertThrows(BadRequestResponse.class, () -> CommandApi.editAreasOf("not json"));
    }

    @Test
    void refusesAnEditLargerThanADraftHolds() {
        final String shape = "{\"name\":\"A\",\"corners\":[]}";
        final String shapes = String.join(",", java.util.Collections.nCopies(CommandApi.MOST_SHAPES + 1, shape));

        assertThrows(
                BadRequestResponse.class,
                () -> CommandApi.editAreasOf("{\"world\":\"nordtal\",\"shapes\":[" + shapes + "]}"));
    }

    @Test
    void aNewPolygonNeedsANameAndEveryOtherStepNone() {
        assertEquals(
                new CommandApi.AreaDraft(SmpRequest.AreaDraft.Step.ADD, "Arena"),
                CommandApi.areaDraftOf("{\"step\":\"ADD\",\"name\":\"Arena\"}"));
        assertEquals(
                SmpRequest.AreaDraft.Step.READ,
                CommandApi.areaDraftOf("{\"step\":\"READ\"}").step());
        assertThrows(BadRequestResponse.class, () -> CommandApi.areaDraftOf("{\"step\":\"ADD\",\"name\":\" \"}"));
        assertThrows(BadRequestResponse.class, () -> CommandApi.areaDraftOf("{\"step\":\"JUMP\"}"));
        assertThrows(BadRequestResponse.class, () -> CommandApi.areaDraftOf("{}"));
    }
}
