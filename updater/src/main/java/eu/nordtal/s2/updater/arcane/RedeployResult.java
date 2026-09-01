package eu.nordtal.s2.updater.arcane;

import org.jetbrains.annotations.NotNull;

/**
 * What came of asking Arcane to redeploy.
 *
 * @param triggered whether the request was accepted. <b>Not</b> whether the network came back -
 *                  nothing running inside the deployment can observe that, because the redeploy
 *                  takes this container down with everything else
 * @param message   one sentence for the request row, and from there for a Discord embed or a chat
 *                  line. Says what to do next when {@code triggered} is false
 */
public record RedeployResult(boolean triggered, @NotNull String message) {

    static RedeployResult triggered(final @NotNull String message) {
        return new RedeployResult(true, message);
    }

    static RedeployResult refused(final @NotNull String message) {
        return new RedeployResult(false, message);
    }
}
