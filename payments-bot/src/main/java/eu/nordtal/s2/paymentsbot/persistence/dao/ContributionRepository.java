package eu.nordtal.s2.paymentsbot.persistence.dao;

import eu.nordtal.jcore.persistence.mariadb.MariaDbRepository;
import eu.nordtal.jcore.persistence.mariadb.MariaDbSessionFactoryConstructor;
import eu.nordtal.s2.paymentsbot.persistence.model.Contribution;
import org.hibernate.Session;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;

public class ContributionRepository extends MariaDbRepository<Contribution> {
    public ContributionRepository(@NotNull final MariaDbSessionFactoryConstructor<Contribution> sessionFactoryConstructor) {
        super(sessionFactoryConstructor);
    }

    public Set<Long> allProcessedPaymentIds() {
        try (final Session session = getSessionFactory().openSession()) {
            return session.createNamedQuery("Contribution.allProcessedPaymentIds", Long.class)
                    .getResultStream()
                    .collect(Collectors.toSet());
        }
    }

    public Contribution findActiveByReceiver(@NotNull final String receiverId) {
        try (final Session session = getSessionFactory().openSession()) {
            final List<Contribution> contributions = session.createNamedQuery("Contribution.findByReceiver", Contribution.class)
                    .setParameter("receiverId", receiverId)
                    .getResultList();

            return resolveActiveContribution(contributions, LocalDateTime.now());
        }
    }

    static Contribution resolveActiveContribution(@NotNull final List<Contribution> contributions,
                                                  @NotNull final LocalDateTime now) {
        final List<Contribution> ordered = contributions.stream()
                .filter(contribution -> contribution.getCreated() != null)
                .sorted(Comparator
                        .comparing(Contribution::getCreated)
                        .thenComparing(Contribution::getEuroAmount, Comparator.reverseOrder()))
                .toList();

        if (ordered.isEmpty()) {
            return null;
        }

        final PriorityQueue<ActiveContribution> queue = new PriorityQueue<>(ACTIVE_CONTRIBUTION_COMPARATOR);
        LocalDateTime currentTime = null;

        for (final Contribution contribution : ordered) {
            final LocalDateTime created = contribution.getCreated();
            if (created.isAfter(now)) {
                break;
            }

            if (currentTime == null) {
                currentTime = created;
            }

            currentTime = advanceTime(queue, currentTime, created);
            queue.add(new ActiveContribution(contribution));
        }

        if (currentTime == null) {
            return null;
        }

        currentTime = advanceTime(queue, currentTime, now);

        final ActiveContribution active = queue.peek();
        return active == null ? null : active.contribution;
    }

    private static LocalDateTime advanceTime(@NotNull final PriorityQueue<ActiveContribution> queue,
                                             @NotNull final LocalDateTime currentTime,
                                             @NotNull final LocalDateTime limit) {
        LocalDateTime cursor = currentTime;

        if (!cursor.isBefore(limit)) {
            return cursor;
        }

        while (!queue.isEmpty() && cursor.isBefore(limit)) {
            final ActiveContribution active = queue.poll();
            final long secondsUntilLimit = Math.max(0, ChronoUnit.SECONDS.between(cursor, limit));

            if (secondsUntilLimit == 0) {
                queue.add(active);
                break;
            }

            if (active.unlimited) {
                queue.add(active);
                cursor = limit;
                break;
            }

            if (active.remainingSeconds <= secondsUntilLimit) {
                cursor = cursor.plusSeconds(active.remainingSeconds);
                active.remainingSeconds = 0;
            } else {
                active.remainingSeconds -= secondsUntilLimit;
                cursor = limit;
                queue.add(active);
                break;
            }
        }

        if (queue.isEmpty() && cursor.isBefore(limit)) {
            cursor = limit;
        }

        return cursor;
    }

    private static final Comparator<ActiveContribution> ACTIVE_CONTRIBUTION_COMPARATOR =
            Comparator.<ActiveContribution>comparingDouble(active -> active.contribution.getEuroAmount())
                    .reversed()
                    .thenComparing(active -> active.contribution.getCreated(), Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(active -> active.contribution.getId(), Comparator.nullsLast(Comparator.naturalOrder()));

    private static final class ActiveContribution {
        private final Contribution contribution;
        private final boolean unlimited;
        private long remainingSeconds;

        private ActiveContribution(@NotNull final Contribution contribution) {
            this.contribution = contribution;
            this.unlimited = contribution.getDurationSeconds() <= 0;
            this.remainingSeconds = unlimited ? Long.MAX_VALUE : contribution.getDurationSeconds();
        }
    }
}
