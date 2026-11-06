package eu.nordtal.season.discordbot.discord;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/** The card of each run in the admin channel, kept across restarts of the bot. */
public interface RunCards {

    /**
     * @param messageId the card's message, or {@code null} when the alert came before it had one
     * @param alerted whether the alert of the failed run took the card's place
     */
    record Card(@Nullable String messageId, boolean alerted) {}

    /** Returns the card of a run, or empty when the run has none. */
    Optional<Card> of(long run);

    /** Keeps the message of a run's card. */
    void keep(long run, String messageId);

    /** Records that the alert of a failed run takes its card's place, and returns false when an alert already had. */
    boolean alert(long run);

    static RunCards stored(final Jdbi jdbi) {
        return new RunCards() {
            @Override
            public Optional<Card> of(final long run) {
                return jdbi.withHandle(
                        handle -> handle.createQuery("SELECT message_id, alerted FROM bot_run_card WHERE run = :run")
                                .bind("run", run)
                                .map((row, context) -> new Card(row.getString("message_id"), row.getBoolean("alerted")))
                                .findOne());
            }

            @Override
            public void keep(final long run, final String messageId) {
                jdbi.useHandle(handle -> handle.createUpdate("""
                                INSERT INTO bot_run_card (run, message_id) VALUES (:run, :messageId)
                                ON CONFLICT (run) DO UPDATE SET message_id = EXCLUDED.message_id
                                """)
                        .bind("run", run)
                        .bind("messageId", messageId)
                        .execute());
            }

            @Override
            public boolean alert(final long run) {
                return jdbi.withHandle(handle ->
                                handle.createUpdate("""
                                        INSERT INTO bot_run_card (run, alerted) VALUES (:run, true)
                                        ON CONFLICT (run) DO UPDATE SET alerted = true WHERE NOT bot_run_card.alerted
                                        """).bind("run", run).execute())
                        == 1;
            }
        };
    }

    /** Keeps the cards for as long as the process runs. */
    static RunCards inMemory() {
        final Map<Long, Card> cards = new ConcurrentHashMap<>();
        return new RunCards() {
            @Override
            public Optional<Card> of(final long run) {
                return Optional.ofNullable(cards.get(run));
            }

            @Override
            public void keep(final long run, final String messageId) {
                cards.merge(run, new Card(messageId, false), (old, fresh) -> new Card(messageId, old.alerted()));
            }

            @Override
            public boolean alert(final long run) {
                final boolean[] first = {false};
                cards.compute(run, (key, old) -> {
                    first[0] = old == null || !old.alerted();
                    return new Card(old == null ? null : old.messageId(), true);
                });
                return first[0];
            }
        };
    }
}
