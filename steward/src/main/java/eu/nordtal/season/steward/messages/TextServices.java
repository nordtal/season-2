package eu.nordtal.season.steward.messages;

import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.spec.Display;
import java.util.List;

/** Selects the services that show a text which their jar has, for the filter of the Translations page. */
final class TextServices {

    private TextServices() {}

    /**
     * Returns whether {@code service} shows a text of {@code bundle} at {@code shown}.
     * A building block and a text without a place count for every service.
     */
    static boolean shows(final String service, final String bundle, final List<String> shown) {
        if (bundle.equals(Messages.VALUES) || bundle.equals(Messages.TERMS) || shown.isEmpty()) {
            return true;
        }
        final Display.Surface draws = surfaceOf(service);
        return shown.stream().map(Display::valueOf).anyMatch(place -> place.surface() == draws);
    }

    /** Returns the services of {@code shipping} that show the text. */
    static List<String> showing(final List<String> shipping, final String bundle, final List<String> shown) {
        return shipping.stream()
                .filter(service -> shows(service, bundle, shown))
                .toList();
    }

    /** Returns the surface of a service: Discord for the bot, Steward for Steward and the game for others. */
    private static Display.Surface surfaceOf(final String service) {
        return switch (service) {
            case Topology.DISCORD_BOT -> Display.Surface.DISCORD;
            case Topology.STEWARD -> Display.Surface.STEWARD;
            default -> Display.Surface.GAME;
        };
    }
}
