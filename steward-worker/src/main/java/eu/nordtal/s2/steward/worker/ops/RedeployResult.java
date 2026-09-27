package eu.nordtal.s2.steward.worker.ops;

/**
 * What came of asking the container runtime to do one thing: stop, start or recreate one service.
 *
 * @param triggered whether the daemon accepted the request. Not whether the service came back - that is a different
 *     question with a different answer: {@link ServiceRuntime#isBack()}, read back
 *     from {@link ContainerOps#runtime()} until it says yes
 * @param verified whether this process could read back what actually happened. Only a {@link DockerOps#stop} answers
 *     this with anything but {@code true}: Docker's stop call succeeds whether the container shut down or was killed
 *     at the end of the grace period, so the container is inspected afterwards - and that inspect can itself fail.
 *     An unverified stop is not a failure; it is a stop nobody watched, and the backup taken over it is marked
 *     rather than thrown away. Only meaningful when {@code triggered} is true. A refusal carries {@code true} here
 *     because there is nothing to have verified - whatever was asked for did not happen - and no caller reads it on
 *     that path
 * @param message one sentence for the request row, and from there for a Discord embed or a chat line. Says what to
 *     do next when {@code triggered} is false
 */
public record RedeployResult(boolean triggered, boolean verified, String message) {

    public static RedeployResult triggered(final String message) {
        return new RedeployResult(true, true, message);
    }

    public static RedeployResult refused(final String message) {
        return new RedeployResult(false, true, message);
    }

    /**
     * The daemon did what was asked and this process could not confirm how it went.
     *
     * Deliberately not a refusal. Refusing here would take the network down over an unreadable {@code inspect},
     * which is a worse outcome than the one being guarded against - but calling it an ordinary success would let a
     * run report a backup nobody could trust. So it is its own answer, and it travels as far as the archive: the
     * file gets a mark
     * beside it saying the stop behind it was never verified.
     */
    public static RedeployResult unverified(final String message) {
        return new RedeployResult(true, false, message);
    }
}
