package eu.nordtal.season.database.announce;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.Journal;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Schedule;
import eu.nordtal.season.messages.document.Document;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/** The admins' announcements: drafts that change until they are sent, and the sent ones with where each went out. */
public final class AnnouncementBook {

    private final Jdbi jdbi;
    private final Inbox<BotRequest> bot;

    private AnnouncementBook(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
        this.bot = Inbox.over(dataSource, BotRequest.TABLE);
    }

    public static AnnouncementBook using(final DataSource dataSource) {
        return new AnnouncementBook(dataSource);
    }

    /**
     * One announcement.
     *
     * @param sent when it was first sent, null for a draft
     * @param targets the channel of each language it goes out in, in the order of their language
     */
    public record Announcement(
            long id,
            Document document,
            Instant changed,
            @Nullable Instant sent,
            List<Target> targets) {

        public Announcement {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(changed, "changed");
            targets = List.copyOf(targets);
        }
    }

    /** One language of an announcement, the channel it goes out in and how far it got. */
    public record Target(String language, String channel, Delivery delivery) {

        public Target {
            Objects.requireNonNull(language, "language");
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(delivery, "delivery");
        }
    }

    /** How far one language of an announcement got. */
    public enum Delivery {
        /** The announcement is a draft. */
        UNSENT,
        /** The bot has a request to post it that it has not answered yet. */
        SENDING,
        /** The bot posted it. */
        POSTED,
        /** The bot could not post it, and no request is open for it. */
        NOT_POSTED
    }

    /**
     * What a send asked the bot for.
     *
     * @param request the bot's request, empty when every language is posted or on its way already
     * @param languages the languages the request posts
     */
    public record Sent(Optional<Long> request, List<String> languages) {

        public Sent {
            languages = List.copyOf(languages);
        }
    }

    /** Returns every announcement, the newest change first. */
    public List<Announcement> all() {
        return jdbi.withHandle(handle -> {
            final Map<Long, List<Target>> targets = targets(handle, null);
            return handle.createQuery("SELECT id, document::text AS document, changed, sent FROM announcement"
                            + " ORDER BY coalesce(sent, changed) DESC, id DESC")
                    .map((rows, context) -> announcement(
                            rows.getLong("id"),
                            rows.getString("document"),
                            rows.getTimestamp("changed"),
                            rows.getTimestamp("sent"),
                            targets))
                    .list();
        });
    }

    /** Returns one announcement, or empty if there is none of that id. */
    public Optional<Announcement> find(final long id) {
        return jdbi.withHandle(handle -> find(handle, id, false));
    }

    /** Writes a new draft with the channel of each language, by language tag, and returns its id. */
    public long create(final Document document, final Map<String, String> channels) {
        Objects.requireNonNull(document, "document");
        return jdbi.inTransaction(handle -> {
            final long id = handle.createQuery(
                            "INSERT INTO announcement (document, changed) VALUES (CAST(:document AS jsonb), now())"
                                    + " RETURNING id")
                    .bind("document", document.json())
                    .mapTo(Long.class)
                    .one();
            channels(handle, id, channels);
            return id;
        });
    }

    /** Replaces a draft's document and channels. Returns false when there is no such draft, or it was sent. */
    public boolean change(final long id, final Document document, final Map<String, String> channels) {
        Objects.requireNonNull(document, "document");
        return jdbi.inTransaction(handle -> {
            final int changed = handle.createUpdate("UPDATE announcement SET document = CAST(:document AS jsonb),"
                            + " changed = now() WHERE id = :id AND sent IS NULL")
                    .bind("document", document.json())
                    .bind("id", id)
                    .execute();
            if (changed == 0) {
                return false;
            }
            handle.createUpdate("DELETE FROM announcement_channel WHERE announcement = :id")
                    .bind("id", id)
                    .execute();
            channels(handle, id, channels);
            return true;
        });
    }

    /** Deletes a draft. Returns false when there is no such draft, or it was sent. */
    public boolean delete(final long id) {
        return jdbi.withHandle(handle -> handle.createUpdate("DELETE FROM announcement WHERE id = :id AND sent IS NULL")
                        .bind("id", id)
                        .execute())
                > 0;
    }

    /**
     * Sends an announcement, or sends again the languages a send could not post, with its journal line.
     * Returns empty when there is no such announcement or it has no channel.
     */
    public Optional<Sent> send(final long id, final Actor actor, final Schedule schedule) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(schedule, "schedule");
        return jdbi.inTransaction(handle -> {
            final Optional<Announcement> found = find(handle, id, true);
            if (found.isEmpty() || found.get().targets().isEmpty()) {
                return Optional.empty();
            }
            final List<BotRequest.PostAnnouncement.Target> open = new ArrayList<>();
            for (final Target target : found.get().targets()) {
                if (target.delivery() == Delivery.UNSENT || target.delivery() == Delivery.NOT_POSTED) {
                    open.add(new BotRequest.PostAnnouncement.Target(target.language(), target.channel()));
                }
            }
            if (open.isEmpty()) {
                return Optional.of(new Sent(Optional.empty(), List.of()));
            }
            handle.createUpdate("UPDATE announcement SET sent = coalesce(sent, now()) WHERE id = :id")
                    .bind("id", id)
                    .execute();
            final List<String> languages = open.stream()
                    .map(BotRequest.PostAnnouncement.Target::language)
                    .toList();
            Journal.write(handle, AuditLine.of(JournalAction.ANNOUNCE, actor, new JournalDetail.Announced(languages)));
            final long request = bot.submitWithin(handle, new BotRequest.PostAnnouncement(id, open), actor, schedule)
                    .id();
            return Optional.of(new Sent(Optional.of(request), languages));
        });
    }

    /** Notes the languages of an announcement that the bot posted, keeping the first time each went out. */
    public void posted(final long id, final Collection<String> languages) {
        if (languages.isEmpty()) {
            return;
        }
        jdbi.useHandle(handle -> handle.createUpdate("UPDATE announcement_channel SET posted = coalesce(posted, now())"
                        + " WHERE announcement = :id AND language = ANY(:languages)")
                .bind("id", id)
                .bindArray("languages", String.class, List.copyOf(languages))
                .execute());
    }

    private Optional<Announcement> find(final Handle handle, final long id, final boolean lock) {
        // The row lock comes before the targets are read, so a second send waits and then sees the first one's request.
        if (lock
                && handle.createQuery("SELECT id FROM announcement WHERE id = :id FOR UPDATE")
                        .bind("id", id)
                        .mapTo(Long.class)
                        .findOne()
                        .isEmpty()) {
            return Optional.empty();
        }
        final Map<Long, List<Target>> targets = targets(handle, id);
        return handle.createQuery(
                        "SELECT id, document::text AS document, changed, sent FROM announcement" + " WHERE id = :id")
                .bind("id", id)
                .map((rows, context) -> announcement(
                        rows.getLong("id"),
                        rows.getString("document"),
                        rows.getTimestamp("changed"),
                        rows.getTimestamp("sent"),
                        targets))
                .findOne();
    }

    private static Announcement announcement(
            final long id,
            final String document,
            final Timestamp changed,
            final @Nullable Timestamp sent,
            final Map<Long, List<Target>> targets) {
        return new Announcement(
                id,
                Document.decode(document),
                changed.toInstant(),
                sent == null ? null : sent.toInstant(),
                targets.getOrDefault(id, List.of()));
    }

    /**
     * Reads the targets of one announcement, or of all when {@code id} is null, with how far each got.
     * A language is on its way while a request that posts it is open.
     */
    private static Map<Long, List<Target>> targets(final Handle handle, final @Nullable Long id) {
        final Map<Long, List<Target>> targets = new LinkedHashMap<>();
        handle.createQuery("""
                        SELECT c.announcement, c.language, c.channel,
                            CASE WHEN a.sent IS NULL THEN 'UNSENT'
                                 WHEN c.posted IS NOT NULL THEN 'POSTED'
                                 WHEN EXISTS (SELECT 1 FROM bot_post_announcement p
                                     JOIN bot_post_announcement_target t ON t.request = p.request
                                     JOIN bot_inbox i ON i.id = p.request
                                     WHERE p.announcement = c.announcement AND t.language = c.language
                                       AND (i.status = 'RUNNING' OR (i.status = 'PENDING'
                                           AND (i.expires IS NULL OR i.expires > now())))) THEN 'SENDING'
                                 ELSE 'NOT_POSTED' END AS delivery
                        FROM announcement_channel c JOIN announcement a ON a.id = c.announcement
                        WHERE CAST(:id AS bigint) IS NULL OR c.announcement = :id
                        ORDER BY c.announcement, c.language""")
                .bind("id", id)
                .map((rows, context) -> new Row(
                        rows.getLong("announcement"),
                        new Target(
                                rows.getString("language"),
                                rows.getString("channel"),
                                Delivery.valueOf(rows.getString("delivery")))))
                .forEach(row -> targets.computeIfAbsent(row.announcement(), _ -> new ArrayList<>())
                        .add(row.target()));
        return targets;
    }

    /** One target with the announcement it belongs to. */
    private record Row(long announcement, Target target) {}

    private static void channels(final Handle handle, final long id, final Map<String, String> channels) {
        channels.forEach((language, channel) -> handle.createUpdate(
                        "INSERT INTO announcement_channel (announcement, language, channel)"
                                + " VALUES (:id, :language, :channel)")
                .bind("id", id)
                .bind("language", language)
                .bind("channel", channel)
                .execute());
    }
}
