package eu.nordtal.season.stewardagent.docker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * One container of {@link FakeDaemon} as a running stack has it, with the figures that its routes answer.
 *
 * @param image the reference it was created from, which also names the digest its registry answers with
 * @param health Docker's health status, or empty for a container without a health check
 * @param startedAgo how long ago it was last started
 * @param endedAgo how long ago it ended, ignored while it runs
 * @param exitCode the exit code of its last end, ignored while it runs
 * @param memoryBytes the memory it uses, ignored while it is stopped
 * @param cpuPercent Docker's CPU share of the whole machine, ignored while it is stopped
 * @param log the lines its log repeats, without timestamps
 */
public record Resident(
        String service,
        String image,
        boolean running,
        String health,
        Duration startedAgo,
        Duration endedAgo,
        int exitCode,
        long memoryBytes,
        double cpuPercent,
        List<String> log) {

    /** The container's id, which every route finds it by. */
    public String id() {
        return "resident-" + service;
    }

    /** The id of the image, derived from the reference so one reference always means one image. */
    public String imageId() {
        return "sha256:" + digest();
    }

    /** The registry digest of the image, hex without the algorithm. */
    public String digest() {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(image.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    /** The reference without its tag, as a repo digest names it. */
    public String repository() {
        final int slash = image.lastIndexOf('/');
        final int colon = image.indexOf(':', slash + 1);
        return colon < 0 ? image : image.substring(0, colon);
    }

    /** Docker's status text for the list, such as {@code Up 4 hours (healthy)} or {@code Exited (143) 46 hours ago}. */
    public String status() {
        if (running) {
            return "Up " + span(startedAgo) + (health.isEmpty() ? "" : " (" + health + ")");
        }
        return "Exited (" + exitCode + ") " + span(endedAgo) + " ago";
    }

    private static String span(final Duration duration) {
        final long hours = duration.toHours();
        if (hours < 1) {
            return Math.max(1, duration.toMinutes()) + " minutes";
        }
        if (hours < 48) {
            return hours + " hours";
        }
        final long days = duration.toDays();
        return days < 14 ? days + " days" : days / 7 + " weeks";
    }
}
