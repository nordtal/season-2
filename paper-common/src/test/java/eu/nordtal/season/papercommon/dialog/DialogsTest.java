package eu.nordtal.season.papercommon.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DialogsTest {

    private final List<String> ran = new ArrayList<>();
    private final Dialogs clicks = new Dialogs(Runnable::run, (player, sound) -> {});

    @Test
    void theFirstClickOfASendSpendsItsTokenAndALaterOneIsIgnored() {
        final Dialogs.Send send = clicks.send();

        assertTrue(send.answer(() -> ran.add("star")));
        assertFalse(send.answer(() -> ran.add("next")));
        assertEquals(List.of("star"), ran);
    }

    @Test
    void eachSendHasATokenOfItsOwn() {
        assertTrue(clicks.send().answer(() -> ran.add("first")));
        assertTrue(clicks.send().answer(() -> ran.add("redrawn")));
        assertEquals(List.of("first", "redrawn"), ran);
    }

    @Test
    void aClickWhileBusyIsIgnoredAndKeepsTheToken() {
        final Dialogs.Send send = clicks.send();
        final boolean[] busy = {true};

        assertFalse(send.answerUnless(() -> busy[0], () -> ran.add("early")));
        busy[0] = false;
        assertTrue(send.answerUnless(() -> busy[0], () -> ran.add("spin")));
        assertFalse(send.answerUnless(() -> busy[0], () -> ran.add("twice")));
        assertEquals(List.of("spin"), ran);
    }
}
