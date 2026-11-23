package eu.nordtal.season.displaytags.nametag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/** The scheduler draws every tick, and each metadata update is a packet to every viewer. */
class PlayerNameTagImplTest {

    @Test
    void anUnchangedTextSendsNoMetadata() {
        final Component previous = Component.text("Waiting");
        final AtomicInteger resends = new AtomicInteger();

        PlayerNameTagImpl.updateIfChanged(previous, Component.text("Waiting"), resends::incrementAndGet);
        PlayerNameTagImpl.updateIfChanged(previous, Component.text("Waiting"), resends::incrementAndGet);
        assertEquals(0, resends.get(), "a tick that renders the same text sends nothing");

        PlayerNameTagImpl.updateIfChanged(previous, Component.text("Open"), resends::incrementAndGet);
        assertEquals(1, resends.get(), "a changed text is sent");
    }
}
