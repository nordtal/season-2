package eu.nordtal.s2.paymentsbot.service;

import eu.nordtal.s2.paymentsbot.persistence.dao.ContributionRepository;
import eu.nordtal.s2.paymentsbot.persistence.model.Contribution;
import eu.nordtal.s2.paymentsbot.model.ContributionTier;
import org.jetbrains.annotations.NotNull;
import eu.nordtal.jcore.persistence.mariadb.MariaDbSessionFactoryConstructor;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ContributionService {

    private final ContributionRepository repository;

    public ContributionService() {
        this.repository = new ContributionRepository(new MariaDbSessionFactoryConstructor<>(
                System.getenv("MARIADB_URI"),
                System.getenv("MARIADB_USERNAME"),
                System.getenv("MARIADB_PASSWORD"),
                Contribution.class
        ));
    }

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
