package eu.nordtal.s2.steward.ops;

/**
 * What came of asking the runtime to stop, start or recreate one service.
 *
 * @param triggered whether the daemon accepted the request, not whether the service came back
 * @param verified whether the outcome could be read back; only a stop's failed inspect makes it {@code false}
 * @param message one sentence for the request row, saying what to do next when not triggered
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
     * Not a refusal, which would take the network down; the archive written after it is marked instead.
     */
    public static RedeployResult unverified(final String message) {
        return new RedeployResult(true, false, message);
    }
}
