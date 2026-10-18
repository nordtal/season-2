package eu.nordtal.season.internalapi.agent;

import org.jspecify.annotations.Nullable;

/**
 * One service of the compose project as the Docker daemon reports it.
 *
 * @param service the compose service name
 * @param containerId the id that the stop and start calls address
 * @param status Docker's container status, {@code running} when it is up
 * @param health Docker's health state, or {@code null} for a service without a healthcheck
 * @param restarts how often Docker's restart policy started the container again since it was created
 */
public record ServiceRuntime(
        String service,
        @Nullable String containerId,
        @Nullable String status,
        @Nullable String health,
        int restarts) {

    /**
     * Returns whether the service is back: healthy, or {@code running} if it has no healthcheck.
     * A plugin that fails in {@code onEnable} leaves it running but not healthy.
     */
    public boolean isBack() {
        if (!"running".equalsIgnoreCase(status)) {
            return false;
        }
        return health == null || health.isBlank() || "healthy".equalsIgnoreCase(health);
    }

    public String describe() {
        final String state = status == null ? "no container" : status;
        return health == null || health.isBlank() ? state : state + ", " + health;
    }
}
