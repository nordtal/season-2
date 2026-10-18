package eu.nordtal.season.database.inbox;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.messages.MessageRef;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A request to the bot, which alone holds a Discord session. Each record is one kind. */
public sealed interface BotRequest {

    InboxTable<BotRequest> TABLE = InboxTable.of("bot_inbox", Channel.BOT, BotRequest.class);

    /** Adds days of access to a person, applies the role and tells them. */
    record Grant(DiscordId person, int days) implements BotRequest {

        public Grant {
            Objects.requireNonNull(person, "person");
            if (days <= 0) {
                throw new IllegalArgumentException("a grant is at least one day, got " + days);
            }
        }
    }

    /** Removes every running grant and the role, and tells the person. */
    record Revoke(DiscordId person) implements BotRequest {

        public Revoke {
            Objects.requireNonNull(person, "person");
        }
    }

    /**
     * Tells a payer what steward booked: the roles, the direct messages, the thank-you and the admin card.
     * The booking is committed with this row, so the bot only reacts.
     *
     * @param donationCents the part of the payment that counts as donation
     * @param downgraded whether fewer days were granted than ordered
     * @param receivedCents the amount, null for an admin's booking by hand
     * @param from the start of the access period it bought
     * @param entry the journal entry of the booking, which the admin card draws
     */
    record PaymentBooked(
            UUID payment,
            DiscordId person,
            String reference,
            int days,
            int donationCents,
            boolean downgraded,
            @Nullable Integer receivedCents,
            Instant from,
            Instant until,
            UUID entry)
            implements BotRequest {

        public PaymentBooked {
            Objects.requireNonNull(payment, "payment");
            Objects.requireNonNull(person, "person");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(until, "until");
            Objects.requireNonNull(entry, "entry");
            if (days <= 0) {
                throw new IllegalArgumentException("a booking grants at least one day, got " + days);
            }
        }

        public boolean donation() {
            return donationCents > 0;
        }
    }

    /** Breaks the link between a Discord account and a Minecraft account. */
    record Unlink(DiscordId person) implements BotRequest {

        public Unlink {
            Objects.requireNonNull(person, "person");
        }
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
     * Posts an alert into the admin channel and mentions the admins who chose Discord for its type.
     *
     * @param link the Steward page of the alert, may be null
     * @param mentions empty for an all-clear, which mentions nobody
     */
    record PostAlert(
            Alert.Level level,
            MessageRef title,
            List<MessageRef> detail,
            @Nullable String link,
            List<DiscordId> mentions)
            implements BotRequest {

        public PostAlert {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(title, "title");
            detail = List.copyOf(detail);
            mentions = List.copyOf(mentions);
        }
    }

    /**
     * Posts one message per language into the announcement channel of that language. A language without one is skipped.
     * The bot renders each message, so an override reaches a request already written.
     *
     * @param messages the message of the {@code announcement} section by language tag
     */
    record Announce(Map<String, MessageRef> messages) implements BotRequest {

        /** The answer for a language whose text went out. */
        public static final String POSTED = "POSTED";

        /** The answer for a language without a channel that the bot can write to. */
        public static final String NOT_POSTED = "NOT_POSTED";

        public Announce {
            messages = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(messages, "messages")));
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("an announcement carries at least one language");
            }
        }
    }

    /** Sends an admin a text or a whole document that the admin tries, as a direct message. Refused if undelivered. */
    record PreviewMessage(DiscordId person, MessagePreview preview) implements BotRequest {

        public PreviewMessage {
            Objects.requireNonNull(person, "person");
            Objects.requireNonNull(preview, "preview");
        }
    }
}
