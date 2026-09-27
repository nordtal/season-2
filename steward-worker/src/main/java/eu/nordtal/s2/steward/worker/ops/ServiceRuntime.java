package eu.nordtal.s2.steward.worker.ops;

import org.jspecify.annotations.Nullable;

/**
 * One service of the compose project as the Docker daemon sees it now, from its list and one inspect.
 *
 * @param service the compose service name
 * @param containerId what the stop and start calls are addressed to
 * @param status Docker's container status, {@code running} when it is up
 * @param health Docker's health state, or {@code null} for a service without a healthcheck
 */
public record ServiceRuntime(
        String service,
        @Nullable String containerId,
        @Nullable String status,
        @Nullable String health) {

    /**
     * Whether this service is back for real: healthy, since a plugin failing in {@code onEnable} leaves it running.
     *
     * A service with no healthcheck is accepted on {@code running}.
     */
    public boolean isBack() {
        if (!"running".equalsIgnoreCase(status)) {
            return false;
        }
        return health == null || health.isBlank() || "healthy".equalsIgnoreCase(health);
    }

    /** What a report line says when this service did not come back. */
    public String describe() {
        final String state = status == null ? "no container" : status;
        return health == null || health.isBlank() ? state : state + ", " + health;
    }
}
