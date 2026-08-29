package eu.nordtal.s2.paymentsbot.persistence.dao;

import eu.nordtal.s2.paymentsbot.persistence.model.Contribution;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ContributionRepositoryTest {

    private static final String RECEIVER = "receiver";

    @Test
    void selectsHigherContributionBeforeReturningToLowerTier() {
        final LocalDateTime base = LocalDateTime.of(2024, 1, 1, 0, 0);
        final Contribution lower = contribution(3f, base, 60);
        final Contribution higher = contribution(5f, base.plusSeconds(20), 60);

        final List<Contribution> contributions = List.of(lower, higher);

        assertSame(higher, ContributionRepository.resolveActiveContribution(contributions, base.plusSeconds(30)));
        assertSame(higher, ContributionRepository.resolveActiveContribution(contributions, base.plusSeconds(70)));
        assertSame(lower, ContributionRepository.resolveActiveContribution(contributions, base.plusSeconds(90)));
        assertNull(ContributionRepository.resolveActiveContribution(contributions, base.plusSeconds(130)));
    }

    @Test
    void waitsForHigherContributionBeforeStartingLowerTier() {
        final LocalDateTime base = LocalDateTime.of(2024, 1, 1, 0, 0);
        final Contribution premium = contribution(9f, base, 120);
        final Contribution queued = contribution(3f, base.plusSeconds(1), 30);

        final List<Contribution> contributions = List.of(premium, queued);

        assertSame(premium, ContributionRepository.resolveActiveContribution(contributions, base.plusSeconds(60)));
        assertSame(queued, ContributionRepository.resolveActiveContribution(contributions, base.plusSeconds(130)));
        assertNull(ContributionRepository.resolveActiveContribution(contributions, base.plusSeconds(200)));
    }

    @Test
    void unlimitedContributionResumesAfterHigherTierExpires() {
        final LocalDateTime base = LocalDateTime.of(2024, 1, 1, 0, 0);
        final Contribution unlimited = contribution(3f, base, 0);
        final Contribution higher = contribution(7f, base.plusSeconds(10), 60);

        final List<Contribution> contributions = List.of(unlimited, higher);

        assertSame(unlimited, ContributionRepository.resolveActiveContribution(contributions, base.plusSeconds(5)));
        assertSame(higher, ContributionRepository.resolveActiveContribution(contributions, base.plusSeconds(30)));
        assertSame(unlimited, ContributionRepository.resolveActiveContribution(contributions, base.plusSeconds(90)));
    }

    @Test
    void contributionsWithSameAmountRespectCreationOrder() {
        final LocalDateTime base = LocalDateTime.of(2024, 1, 1, 0, 0);
        final Contribution first = contribution(5f, base, 60);
        final Contribution second = contribution(5f, base.plusSeconds(30), 60);

        final List<Contribution> contributions = List.of(first, second);

        assertSame(first, ContributionRepository.resolveActiveContribution(contributions, base.plusSeconds(45)));
        assertSame(second, ContributionRepository.resolveActiveContribution(contributions, base.plusSeconds(90)));
        assertNull(ContributionRepository.resolveActiveContribution(contributions, base.plusSeconds(200)));
    }

    @Test
    void ignoresContributionsStartingInTheFuture() {
        final LocalDateTime base = LocalDateTime.of(2024, 1, 1, 0, 0);
        final Contribution future = contribution(3f, base.plusHours(2), 60);

        assertNull(ContributionRepository.resolveActiveContribution(List.of(future), base));
    }

    @Test
    void returnsNullWhenNoContributionsPresent() {
        assertNull(ContributionRepository.resolveActiveContribution(List.of(), LocalDateTime.now()));
    }

    private static Contribution contribution(final float amount,
                                              final LocalDateTime created,
                                              final long durationSeconds) {
        final Contribution contribution = new Contribution();
        contribution.setId(UUID.randomUUID());
        contribution.setReceiverId(RECEIVER);
        contribution.setEuroAmount(amount);
        contribution.setCreated(created);
        contribution.setDurationSeconds(durationSeconds);
        return contribution;
    }
}
