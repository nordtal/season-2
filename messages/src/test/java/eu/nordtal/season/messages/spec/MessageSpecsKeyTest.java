package eu.nordtal.season.messages.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MessageSpecsKeyTest {

    @Test
    void kebabCaseSplitsWordsAndDigitRuns() {
        assertEquals("no-such-member", MessageSpecs.kebab("noSuchMember"));
        assertEquals("tier-12-hours", MessageSpecs.kebab("tier12Hours"));
    }
}
