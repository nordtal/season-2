package eu.nordtal.s2.internalapi.agent;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** {@link Snapshots} over the agent, with the database and the patience taken from the plan at every call. */
final class AgentSnapshots implements Snapshots {

    private final AgentClient agent;
    private final Supplier<AgentClient.Backup> plan;

    AgentSnapshots(final AgentClient agent, final Supplier<AgentClient.Backup> plan) {
        this.agent = agent;
        this.plan = plan;
    }

    @Override
    public SnapshotResult saveDatabase() {
        final AgentClient.Backup now = plan.get();
        if (now.databaseService().isBlank()) {
            return SnapshotResult.failed(
                    DATABASE,
                    Duration.ZERO,
                    "backup.database-service is empty, so no database dump was taken. Nothing is wrong with the"
                            + " database; nothing was saved of it either.");
        }
        return agent.dumpDatabase(now.databaseService(), now.role());
    }

    @Override
    public SnapshotResult save(final String volume) {
        return agent.snapshot(volume, plan.get().patience());
    }

    @Override
    public @Nullable String markUnverified(final String archive, final String why) {
        return agent.mark(archive, why);
    }

    @Override
    public List<String> prune(final Retention policy) {
        return agent.prune(policy);
    }
}
