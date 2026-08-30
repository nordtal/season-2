package eu.nordtal.s2.accessbot.persistence.dao;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.JdbiRepository;
import eu.nordtal.s2.accessbot.persistence.model.Contribution;

import org.jdbi.v3.core.mapper.reflect.CaseInsensitiveColumnNameMatcher;
import org.jdbi.v3.core.mapper.reflect.ReflectionMappers;
import org.jdbi.v3.core.mapper.reflect.SnakeCaseColumnNameMatcher;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * Repository over {@link ContributionDao}, plus the contribution-scheduling logic that decides
 * which of a member's contributions is the active one right now.
 * <p>
 * It extends jcore's optional {@link JdbiRepository} rather than calling
 * {@code database.jdbi().onDemand(...)} itself: this class already existed as a repository with
 * non-DAO logic on it (see {@link #resolveActiveContribution(List, LocalDateTime)}), so the base
 * class removes the on-demand wiring without adding a layer. The DAO interface stays the
 * abstraction - nothing here re-introduces a string-keyed generic {@code findFirst}.
 * </p>
 */
public class ContributionRepository extends JdbiRepository<ContributionDao> {

    /**
     * @param database the application-wide {@link Database}; this class does not own it and never
     *                 closes it
     */
    public ContributionRepository(@NotNull final Database database) {
        super(database, ContributionDao.class);

        // JDBI maps result-set columns to bean properties on its own; jcore's Jackson SNAKE_CASE
        // setting has nothing to do with it. The matchers below are what turns `receiver_id` into
        // `setReceiverId`. JDBI happens to install the same two by default, but the mapping of
        // every column in this module depends on it, so it is stated rather than assumed.
        jdbi().getConfig(ReflectionMappers.class).setColumnNameMatchers(List.of(
                new CaseInsensitiveColumnNameMatcher(),
                new SnakeCaseColumnNameMatcher()));
    }

    /**
     * Inserts the contribution and writes the generated id back onto the given instance.
     * <p>
     * Insert-only - the bot never updates a contribution, it only ever appends one. Unlike the
     * Hibernate {@code save} this replaces, the returned instance is the one passed in and its
     * {@link Contribution#getId()} is populated.
     * </p>
     */
    public Contribution save(@NotNull final Contribution contribution) {
        contribution.setId(dao().insert(contribution));
        return contribution;
    }

    public void delete(@NotNull final Contribution contribution) {
        if (contribution.getId() == null) {
            return;
        }
        dao().deleteById(contribution.getId());
    }

    public Contribution findFirstById(@NotNull final UUID id) {
        return dao().findById(id).orElse(null);
    }

    public List<Contribution> all() {
        return dao().findAll();
    }

    public Set<Long> allProcessedPaymentIds() {
        return dao().allProcessedPaymentIds();
    }

    /**
     * Every contribution of one receiver in database order (oldest first, higher amounts first on
     * a tie). {@link #findActiveByReceiver(String)} is what callers normally want; this is the raw
     * list behind it.
     */
    public List<Contribution> findByReceiverOrdered(@NotNull final String receiverId) {
        return dao().findByReceiver(receiverId);
    }

    public Contribution findActiveByReceiver(@NotNull final String receiverId) {
        return resolveActiveContribution(findByReceiverOrdered(receiverId), LocalDateTime.now());
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
