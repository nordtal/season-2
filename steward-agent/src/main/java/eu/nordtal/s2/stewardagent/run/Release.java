package eu.nordtal.s2.stewardagent.run;

import eu.nordtal.s2.internalapi.agent.Topology;
import eu.nordtal.s2.stewardagent.plan.UpdatePlan;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Whether this agent may install a release: only its own, since it carries that release's schema and no other.
 *
 * A newer release renews steward-agent first, which the host's deployment does; the agent never recreates itself.
 */
final class Release {

    private Release() {}

    /**
     * Returns why an update must not go ahead, or {@code null} when every season jar it installs is this agent's own.
     *
     * @param own this process's version, or {@code null} when unknown, which refuses nothing
     */
    static @Nullable String refusal(final UpdatePlan plan, final @Nullable String own) {
        if (own == null) {
            return null;
        }
        return plan.changes().stream()
                .filter(change -> change.status().isWork() && change.wanted() != null)
                .filter(change -> Topology.SEASON_JARS.contains(change.artifact()))
                .map(change -> Objects.requireNonNull(change.wanted()).version())
                .filter(wanted -> !wanted.equals(own))
                .findFirst()
                .map(wanted -> "NOTHING WAS STOPPED AND NOTHING WAS INSTALLED. The release is " + wanted
                        + " and steward-agent is " + own + ", and an agent installs only the release whose"
                        + " schema it carries. `./nordtal.sh` on the host renews steward-agent; ask again"
                        + " once it is back.")
                .orElse(null);
    }

    /**
     * The release this process runs as, or {@code null} from a test.
     *
     * That is the {@code NORDTAL_RELEASE} its container was made with, its image's tag, or else its manifest version.
     */
    static @Nullable String ownVersion() {
        final String release = System.getenv("NORDTAL_RELEASE");
        if (release != null && !release.isBlank()) {
            return release;
        }
        return Release.class.getPackage().getImplementationVersion();
    }
}
