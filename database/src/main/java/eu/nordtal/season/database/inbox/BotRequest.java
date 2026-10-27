package eu.nordtal.season.database.inbox;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseMessages;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.context.MilestoneContext;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.spec.Display;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A request to the bot, which alone holds a Discord session. Each record is one kind. */
public sealed interface BotRequest {

    /** The bot's inbox table. Its outcome column holds what an alert answers. */
    InboxTable<BotRequest> TABLE = InboxTable.of("bot_inbox", Channel.BOT, BotRequest.class)
            .withRows(
                    Grant.class,
                    Revoke.class,
                    PaymentBooked.class,
                    Unlink.class,
                    SetPlaytime.class,
                    Announce.class,
                    PreviewMessage.class,
                    PreviewDocument.class)
            .withOutcome();

    /** Adds days of access to a person, applies the role and tells them. */
    record Grant(DiscordId person, int days) implements BotRequest {

        public Grant {
            Objects.requireNonNull(person, "person");
            if (days <= 0) {
                throw new IllegalArgumentException("a grant is at least one day, got " + days);
            }
        }

        /** @param until the end of the person's access after the grant */
        public record Answer(Instant until) {}
    }

    /** Removes every running grant and the role, and tells the person. */
    record Revoke(DiscordId person) implements BotRequest {

        public Revoke {
            Objects.requireNonNull(person, "person");
        }

        /** @param revoked the running grants it ended */
        public record Answer(int revoked) {}
    }

    /**
     * Tells a payer what steward booked: the roles, the direct messages, the thank-you and the admin card.
     * The booking is committed with this row, so the bot only reacts and reads what it tells from the booking.
     *
     * @param entry the journal entry of the booking, which holds the days and the donation it booked
     */
    record PaymentBooked(UUID payment, UUID entry) implements BotRequest {

        public PaymentBooked {
            Objects.requireNonNull(payment, "payment");
            Objects.requireNonNull(entry, "entry");
        }
    }

    /** Breaks the link between a Discord account and a Minecraft account. */
    record Unlink(DiscordId person) implements BotRequest {

        public Unlink {
            Objects.requireNonNull(person, "person");
        }

        /** @param unlinked whether there was a link to break */
        public record Answer(boolean unlinked) {}
    }

    /** Writes the total play time of a person. */
    record SetPlaytime(DiscordId person, long seconds) implements BotRequest {

        public SetPlaytime {
            Objects.requireNonNull(person, "person");
            if (seconds < 0) {
                throw new IllegalArgumentException("a play time is never negative, got " + seconds);
            }
        }
    }

    /**
     * Posts an alert into the admin channel. The bot draws the card from the alert's journal entry.
     *
     * @param entry the alert's journal entry
     * @param mention whether the card mentions the admin role, which an all-clear does not
     */
    record PostAlert(UUID entry, boolean mention) implements BotRequest {

        public PostAlert {
            Objects.requireNonNull(entry, "entry");
        }
    }

    /**
     * Posts an announcement into the announcement channel of each of its languages. A language without one is skipped.
     * The bot renders the text of each line when it posts, so an override reaches a request already written.
     *
     * @param milestone the milestone that a milestone message names, null for an admin's words
     */
    record Announce(Message message, @Nullable String milestone, List<Line> lines) implements BotRequest {

        public Announce {
            Objects.requireNonNull(message, "message");
            lines = List.copyOf(lines);
            if (lines.isEmpty()) {
                throw new IllegalArgumentException("an announcement carries at least one language");
            }
            if ((message == Message.WORDS) == (milestone != null)) {
                throw new IllegalArgumentException(message + " names a milestone only if it announces one");
            }
            if (lines.stream().map(Line::language).distinct().count() < lines.size()) {
                throw new IllegalArgumentException("an announcement has one line per language: " + lines);
            }
            for (final Line line : lines) {
                if ((line.words() != null) != (message == Message.WORDS)) {
                    throw new IllegalArgumentException(message + " is not posted from " + line);
                }
            }
        }

        /** Returns an admin's announcement with the words of each language, by language tag. */
        public static Announce words(final Map<String, String> words) {
            final List<Line> lines = new ArrayList<>();
            words.forEach((language, text) -> lines.add(new Line(language, text, null)));
            return new Announce(Message.WORDS, null, lines);
        }

        /** Returns the announcement of a milestone with its name in each language, by language tag. */
        public static Announce milestone(
                final Message message, final String milestone, final Map<String, String> names) {
            final List<Line> lines = new ArrayList<>();
            names.forEach((language, name) -> lines.add(new Line(language, null, name)));
            return new Announce(message, Objects.requireNonNull(milestone, "milestone"), lines);
        }

        /** Returns the message of one line, from the {@code announcement} section of the database bundle. */
        public MessageRef text(final Line line) {
            final DatabaseMessages.Announcements announcement = DatabaseMessages.MESSAGES.announcement();
            return switch (message) {
                case WORDS -> announcement.words(Objects.requireNonNull(line.words(), "words"));
                case MILESTONE -> announcement.milestone(named(line));
                case MILESTONE_BORDER -> announcement.milestoneSection().border(named(line));
                case MILESTONE_NETHER -> announcement.milestoneSection().nether(named(line));
                case MILESTONE_END -> announcement.milestoneSection().end(named(line));
            };
        }

        private static MilestoneContext named(final Line line) {
            return new MilestoneContext(Objects.requireNonNull(line.milestoneName(), "milestoneName"));
        }

        /** The text of the {@code announcement} section that a request posts. */
        public enum Message {
            WORDS,
            MILESTONE,
            MILESTONE_BORDER,
            MILESTONE_NETHER,
            MILESTONE_END
        }

        /**
         * One language of an announcement.
         *
         * @param words the admin's words in that language, for {@link Message#WORDS} only
         * @param milestoneName the milestone's name in that language, for every other message
         */
        public record Line(
                String language,
                @Nullable String words,
                @Nullable String milestoneName) {

            public Line {
                Objects.requireNonNull(language, "language");
                if ((words == null) == (milestoneName == null)) {
                    throw new IllegalArgumentException("a line holds words or a milestone's name: " + language);
                }
            }
        }

        /** @param lines whether the text of each language was posted */
        public record Answer(List<Posted> lines) {

            public Answer {
                lines = List.copyOf(lines);
            }
        }

        /** @param posted false for a language without a channel that the bot can write to */
        public record Posted(String language, boolean posted) {

            public Posted {
                Objects.requireNonNull(language, "language");
            }
        }
    }

    /** Sends an admin a text that the admin tries, as a direct message. Refused if undelivered. */
    record PreviewMessage(
            DiscordId person,
            String key,
            String language,
            String text,
            Display shown,
            String service,
            List<MessagePreview.Example> examples)
            implements BotRequest {

        public PreviewMessage {
            Objects.requireNonNull(person, "person");
            examples = new MessagePreview(key, language, text, shown, service, examples).examples();
        }

        public PreviewMessage(final DiscordId person, final MessagePreview preview) {
            this(
                    person,
                    preview.key(),
                    preview.language(),
                    preview.text(),
                    preview.shown(),
                    preview.service(),
                    preview.examples());
        }

        /** Returns the tried text. */
        public MessagePreview preview() {
            return new MessagePreview(key, language, text, shown, service, examples);
        }
    }

    /** Sends an admin a drafted document, as a direct message. Refused if undelivered. */
    record PreviewDocument(
            DiscordId person, String namespace, String name, String language, Display shown, Document document)
            implements BotRequest {

        public PreviewDocument {
            Objects.requireNonNull(person, "person");
            document = new DocumentPreview(namespace, name, language, shown, document).document();
        }

        public PreviewDocument(final DiscordId person, final DocumentPreview preview) {
            this(person, preview.namespace(), preview.name(), preview.language(), preview.shown(), preview.document());
        }

        /** Returns the drafted document. */
        public DocumentPreview preview() {
            return new DocumentPreview(namespace, name, language, shown, document);
        }
    }
}
