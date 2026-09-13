package eu.nordtal.s2.steward.worker.ops;

import org.jetbrains.annotations.NotNull;

/**
 * What came of asking the container runtime to do one thing: stop, start or recreate one service.
 *
 * @param triggered whether the daemon accepted the request. <b>Not</b> whether the service came back -
 *                  that is a different question with a different answer, and since 2026-09-07 it
 *                  has one: {@link ServiceRuntime#isBack()}, read back from
 *                  {@link ContainerOps#runtime()} until it says yes. Before that a restart was one
 *                  redeploy of the whole project, which took steward-worker down with it - so it
 *                  was never there to ask
 * @param message   one sentence for the request row, and from there for a Discord embed or a chat
 *                  line. Says what to do next when {@code triggered} is false
 */
public record RedeployResult(boolean triggered, @NotNull String message) {

    public static RedeployResult triggered(final @NotNull String message) {
        return new RedeployResult(true, message);
    }

    public static RedeployResult refused(final @NotNull String message) {
        return new RedeployResult(false, message);
    }
}
