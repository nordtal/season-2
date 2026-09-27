package eu.nordtal.s2.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StackTest {

    @Test
    void pointingThePackRewritesItsTwoLinesAndLeavesEveryOtherOneAlone() {
        final String before = """
                enabled: true
                url: ""
                sha1: ""
                force: true
                # url: in a comment
                """;
        final String after = """
                enabled: true
                url: "http://localhost:8081/p$1.zip"
                sha1: "abc"
                force: true
                # url: in a comment
                """;
        assertEquals(after, Stack.pointAt(before, "http://localhost:8081/p$1.zip", "abc"));
    }
}
