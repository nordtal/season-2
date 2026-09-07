package eu.nordtal.s2.updater.arcane;

import org.jetbrains.annotations.NotNull;

/**
 * What came of asking Arcane to do one thing: redeploy the project, or stop or start one container.
 *
 * @param triggered whether Arcane accepted the request. <b>Not</b> whether the service came back -
 *                  that is a different question with a different answer, and since 2026-09-07 it
 *                  has one: {@link ServiceRuntime#isBack()}, read back from
 *                  {@link ArcaneOps#runtime()} until it says yes. Before that the updater took
 *                  itself down with the redeploy and could never ask
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
