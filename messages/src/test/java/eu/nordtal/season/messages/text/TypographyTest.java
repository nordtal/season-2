package eu.nordtal.season.messages.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TypographyTest {

    @Test
    void twoSpacesInsideALineAlign() {
        assertEquals(List.of(Typography.ALIGNED), Typography.problems("Name:  Alex"));
        assertEquals(List.of(Typography.ALIGNED), Typography.problems("<newline>  Alex<newline>"));
    }

    @Test
    void aLineOfSpacesAloneIsASpacerAndAlignsNothing() {
        assertEquals(List.of(), Typography.problems("<newline>          <newline><brand>nordtal.eu</brand>"));
        assertEquals(List.of(), Typography.problems("     \nAlex"));
        assertEquals(List.of(), Typography.problems("Alex<newline>     "));
    }
}
