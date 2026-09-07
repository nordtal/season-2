package eu.nordtal.s2.updater.arcane;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One service of the compose project, as Arcane sees it right now.
 *
 * <p>Read from {@code GET /api/environments/{id}/projects/{projectId}/runtime}, whose entries carry
 * the compose service name, the container id, the container status and Docker's health state
 * (source read 2026-09-07 from Arcane v2.10.0 and v2.10.2, {@code types/project/project.go}).</p>
 *
 * @param service     the compose service name - {@code smp}, {@code limbo}, and so on
 * @param containerId what the stop and start calls are addressed to
 * @param status      Docker's container status, {@code running} when it is up
 * @param health      Docker's health state, or {@code null} for a service that declares no
 *                    healthcheck. Every one of ours does
 */
public record ServiceRuntime(@NotNull String service, @Nullable String containerId,
                             @Nullable String status, @Nullable String health) {

    /**
     * Whether this service is back for real.
     *
     * <h2>Running is not enough, and that is the whole point of asking</h2>
     * A container whose plugin threw in {@code onEnable} is {@code running}: Paper disables the
     * plugin and carries on, the port is open, and there is a server there with no season on it.
     * That is not a hypothetical - it is what the first deployment did, and it is why every one of
     * the five processes writes {@code /tmp/nordtal-ready} and refreshes it every 30 seconds, and
     * why {@code compose.yml} checks that file's <em>age</em>. This method is the one place that
     * evidence is finally read by something that can act on it.
     *
     * <p>A service with no healthcheck at all - {@code health} null - is accepted on
     * {@code running}, because the alternative is an update that can never finish against a
     * compose file somebody edited.</p>
     */
    public boolean isBack() {
        if (!"running".equalsIgnoreCase(status)) {
            return false;
        }
        return health == null || health.isBlank() || "healthy".equalsIgnoreCase(health);
    }

    /** What a report line says when this service did not come back. */
    public @NotNull String describe() {
        final String state = status == null ? "no container" : status;
        return health == null || health.isBlank() ? state : state + ", " + health;
    }
}
