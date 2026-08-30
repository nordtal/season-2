package eu.nordtal.s2.accessbot.service;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.accessbot.model.ContributionTier;
import eu.nordtal.s2.accessbot.persistence.dao.ContributionRepository;
import eu.nordtal.s2.accessbot.persistence.model.Contribution;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Application-level view of the contribution table.
 * <p>
 * It takes the {@link Database} rather than building one: with jcore 2.0.0 there is exactly one
 * connection pool per application, owned and closed by {@link eu.nordtal.s2.accessbot.AccessBot}.
 * The 1.x code built a {@code MariaDbSessionFactoryConstructor} in this constructor, which meant
 * a pool per service instance and nothing that ever closed it.
 * </p>
 */
public class ContributionService {

    private final ContributionRepository repository;

    public ContributionService(@NotNull final Database database) {
        this.repository = new ContributionRepository(database);
    }

    /**
     * Inserts the contribution. The returned instance is the one passed in, with
     * {@link Contribution#getId()} filled in from the database.
     */
    public Contribution save(@NotNull final Contribution contribution) {
        return repository.save(contribution);
    }

    public void delete(@NotNull final Contribution contribution) {
        repository.delete(contribution);
    }

    public Contribution find(@NotNull final UUID id) {
        return repository.findFirstById(id);
    }

    public List<Contribution> all() {
        return repository.all();
    }

    public Set<Long> allProcessedPaymentIds() {
        return repository.allProcessedPaymentIds();
    }

    public ContributionTier highestActiveTier(@NotNull final String receiverId) {
        final Contribution contribution = repository.findActiveByReceiver(receiverId);
        return contribution == null ? null : contribution.contributionTier();
    }

}
