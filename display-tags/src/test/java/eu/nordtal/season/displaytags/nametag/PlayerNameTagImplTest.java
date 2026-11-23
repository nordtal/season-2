package eu.nordtal.season.displaytags.nametag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** Tests that {@link PlayerNameTagImpl} skips metadata resends while keeping passengers mounted. */
class PlayerNameTagImplTest {

    @Test
    void unchangedTextSkipsMetadataResendsAndKeepsRemounting() {
        final Player owner = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> null);
        final AtomicInteger metadataUpdates = new AtomicInteger();
        final AtomicInteger remounts = new AtomicInteger();
        final PlayerNameTagImpl tag = new PlayerNameTagImpl(owner, null, null) {
            @Override
            public void updateFor(final UUID viewerId) {
                metadataUpdates.incrementAndGet();
            }

            @Override
            void mountFor(final UUID viewerId) {
                remounts.incrementAndGet();
            }
        };

        final UUID viewerId = UUID.randomUUID();
        tag.viewers.add(viewerId);

        tag.syncViewer(viewerId, true, tag.refreshText(Component.text("Waiting")));
        assertEquals(1, metadataUpdates.get(), "initial changed text sends metadata");
        assertEquals(0, remounts.get(), "full update already mounts the display");

        tag.syncViewer(viewerId, true, tag.refreshText(Component.text("Waiting")));
        tag.syncViewer(viewerId, true, tag.refreshText(Component.text("Waiting")));
        assertEquals(1, metadataUpdates.get(), "unchanged text sends no metadata");
        assertEquals(2, remounts.get(), "unchanged ticks still re-mount the display");

        tag.syncViewer(viewerId, true, tag.refreshText(Component.text("Open")));
        assertEquals(2, metadataUpdates.get(), "changed text sends metadata again");
    }
}
