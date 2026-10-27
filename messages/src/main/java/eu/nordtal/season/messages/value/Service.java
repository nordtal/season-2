package eu.nordtal.season.messages.value;

import org.jspecify.annotations.Nullable;

/** The service a text names: the network, one of the season's services, or another by its compose name. */
public enum Service {
    NETWORK,
    SMP,
    LIMBO,
    HUNGER_GAMES,
    PROXY,
    OTHER;

    /** @param service the compose service, {@code null} for the whole network */
    public static Service of(final @Nullable String service) {
        if (service == null) {
            return NETWORK;
        }
        return switch (service) {
            case "smp" -> SMP;
            case "limbo" -> LIMBO;
            case "hunger-games" -> HUNGER_GAMES;
            case "proxy" -> PROXY;
            default -> OTHER;
        };
    }
}
