package eu.nordtal.season.steward.messages;

import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.spec.Display;
import java.util.List;

/** Which services show a text their jar ships, the services the Texts page's filter keeps it for. */
final class TextServices {

    private TextServices() {}

    /**
     * Returns whether {@code service} shows a text of {@code bundle} shown at {@code shown}, given its jar ships it.
     *
     * A building block, or a text that names no place, stays with every service that ships it.
     */
    static boolean shows(final String service, final String bundle, final List<String> shown) {
        if (bundle.equals(Messages.VALUES) || shown.isEmpty()) {
            return true;
        }
        final Display.Surface draws = surfaceOf(service);
        return shown.stream().map(Display::valueOf).anyMatch(place -> place.surface() == draws);
    }

    /** Returns the services of {@code shipping} that show the text, in their order. */
    static List<String> showing(final List<String> shipping, final String bundle, final List<String> shown) {
        return shipping.stream()
                .filter(service -> shows(service, bundle, shown))
                .toList();
    }

    /** Where a service draws its texts: the bot in Discord, Steward on its page, every other service in the game. */
    private static Display.Surface surfaceOf(final String service) {
        return switch (service) {
            case Topology.DISCORD_BOT -> Display.Surface.DISCORD;
            case Topology.STEWARD -> Display.Surface.STEWARD;
            default -> Display.Surface.GAME;
        };
    }
}
