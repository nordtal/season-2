package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.RuntimeResult;
import eu.nordtal.season.internalapi.agent.ServiceRuntime;
import eu.nordtal.season.stewardagent.plan.Work;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Asks each running service that names its secrets ({@code eu.nordtal.secrets}) which of them its own file lacks.
 * steward-agent never reads another service's own file, so only the service can say. Each missing name becomes a
 * warning that ./nordtal.sh update on the host adds it.
 */
final class MissingSecrets {

    private MissingSecrets() {}

    /**
     * Returns a warning for each running service in the run whose own file lacks a name its label gives.
     *
     * @param scope the services this run is for, empty for the whole network
     * @param holds the services a hold keeps out of the run
     */
    static List<Work.Finding> of(
            final ContainerOps containers,
            final AgentWire.Topology topology,
            final List<String> scope,
            final List<String> holds) {
        final List<AgentWire.Service> named = topology.services().stream()
                .filter(service -> !service.secrets().isEmpty())
                .filter(service -> scope.isEmpty() || scope.contains(service.name()))
                .filter(service -> !holds.contains(service.name()))
                .toList();
        if (named.isEmpty()) {
            return List.of();
        }
        final RuntimeResult runtime = containers.runtime();
        final List<Work.Finding> findings = new ArrayList<>();
        for (final AgentWire.Service service : named) {
            // A stopped container cannot be asked, and one older than the question answers nothing.
            final Optional<ServiceRuntime> running = runtime.service(service.name())
                    .filter(found -> found.containerId() != null && "running".equalsIgnoreCase(found.status()));
            running.flatMap(found ->
                            containers.missingSecrets(Objects.requireNonNull(found.containerId()), service.secrets()))
                    .filter(missing -> !missing.isEmpty())
                    .ifPresent(missing -> findings.add(new Work.Finding(
                            service.name(), RunStep.Outcome.WARNING, RunStep.Reason.MISSING_SECRET, missing)));
        }
        return findings;
    }
}
