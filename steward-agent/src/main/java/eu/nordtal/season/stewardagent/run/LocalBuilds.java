package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.stewardagent.plan.Change;
import eu.nordtal.season.stewardagent.plan.UpdatePlan;
import java.util.List;
import java.util.Map;

/** What an update run would overwrite that was built on this host, which it replaces only when asked to. */
final class LocalBuilds {

    private LocalBuilds() {}

    /**
     * The services whose local build this run would replace, sorted.
     *
     * An image goes when its container is made again, so on a newer release always; a jar goes when its row is work.
     * @param planned the plan as a report, whose moving lines are the services a run recreates
     * @param renewed the services whose foreign image the run renews once the rest is back
     * @param newer whether the run installs a newer release, which makes every container again
     * @param scope the services the run is for, empty for the whole network
     * @param held the services the run leaves alone
     */
    static List<String> replaced(
            final ImageResult images,
            final UpdatePlan plan,
            final UpdateReport planned,
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
            final UpdateReport planned,
            final List<String> renewed,
            final boolean newer) {
        final String service = build.getKey();
        // An outdated agent is handed over to a one-shot, which renews it last; it has no line of its own.
        final boolean agentRenewed = AgentWire.SERVICE.equals(service) && images.isOutdated(service);
        return build.getValue().image() != null
                && (newer || agentRenewed || planned.line(service).isMoving() || renewed.contains(service));
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
