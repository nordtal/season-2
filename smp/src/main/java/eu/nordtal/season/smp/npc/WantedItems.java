package eu.nordtal.season.smp.npc;

import static eu.nordtal.season.smp.SmpMessages.MESSAGES;

import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.papercommon.game.GameKeys;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;

/** The items an objective takes, one tooltip line each, since the client does not wrap a line. */
final class WantedItems {

    private WantedItems() {}

    static List<Component> lines(final MessageRenderer renderer, final Locale locale, final List<String> items) {
        return items.stream()
                .map(GameKeys::item)
                .map(item -> renderer.format(locale, MESSAGES.smp().objectives().wantedItem(item)))
                .toList();
    }
}
