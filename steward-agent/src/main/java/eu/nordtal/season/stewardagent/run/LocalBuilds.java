package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.stewardagent.plan.Change;
import eu.nordtal.season.stewardagent.plan.UpdatePlan;
import eu.nordtal.season.stewardagent.plan.Work;
import java.util.List;
import java.util.Map;

/**
 * Finds the builds made on this host that an update run would overwrite. It overwrites them only when asked.
 */
final class LocalBuilds {

    private LocalBuilds() {}

    /**
     * The services whose local build this run would replace, sorted.
     *
     * An image goes with its container, so always on a newer release. A jar goes when its row is work.
     * @param planned what the plan moves, whose moving services the run recreates
     * @param renewed the services whose foreign image the run recreates last
     * @param newer whether the run installs a newer release
     * @param scope the services of the run, empty for the whole network
     * @param held the services the run leaves alone
     */
    static List<String> replaced(
            final ImageResult images,
            final UpdatePlan plan,
            final Work planned,
            final List<String> renewed,
            final boolean newer,
            final List<String> scope,
            final List<String> held) {
        return images.localBuilds().entrySet().stream()
                .filter(entry -> scope.isEmpty() || scope.contains(entry.getKey()))
                .filter(entry -> !held.contains(entry.getKey()))
                .filter(entry -> imageGoes(entry, images, planned, renewed, newer) || jarGoes(entry, plan))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private static boolean imageGoes(
            final Map.Entry<String, ImageResult.LocalBuild> build,
            final ImageResult images,
            final Work planned,
            final List<String> renewed,
            final boolean newer) {
        final String service = build.getKey();
        // An outdated agent is recreated by a one-shot and has no line of its own.
        final boolean agentRenewed = AgentWire.SERVICE.equals(service) && images.isOutdated(service);
        return build.getValue().image() != null
                && (newer || agentRenewed || planned.moving().contains(service) || renewed.contains(service));
    }

    private static boolean jarGoes(final Map.Entry<String, ImageResult.LocalBuild> build, final UpdatePlan plan) {
        return plan.changes().stream()
                .filter(change -> build.getKey().equals(change.service()))
                .filter(change -> change.status().isWork())
                .map(Change::installed)
                .anyMatch(installed ->
                        installed != null && build.getValue().jars().contains(installed));
    }
}
