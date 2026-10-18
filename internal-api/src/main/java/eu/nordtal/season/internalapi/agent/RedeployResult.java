package eu.nordtal.season.internalapi.agent;

/**
 * The result of asking the runtime to stop, start or recreate one service.
 *
 * @param triggered whether the daemon accepted the request, not whether the service came back
 * @param verified whether the result could be read back. Only a failed inspect after a stop makes it {@code false}.
 * @param message one sentence for the request row
 */
public record RedeployResult(boolean triggered, boolean verified, String message) {

    public static RedeployResult triggered(final String message) {
        return new RedeployResult(true, true, message);
    }

    public static RedeployResult refused(final String message) {
        return new RedeployResult(false, true, message);
    }

    /**
     * The daemon did what was asked, and this process could not confirm the result.
     * The archive written after it is marked.
     */
    public static RedeployResult unverified(final String message) {
        return new RedeployResult(true, false, message);
    }
}
