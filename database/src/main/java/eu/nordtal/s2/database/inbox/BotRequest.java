package eu.nordtal.s2.database.inbox;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.notify.Channel;
import eu.nordtal.s2.messages.MessageRef;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What the bot can be asked to do, since only it holds a Discord session; each record is one kind. */
public sealed interface BotRequest {

    /** The bot's inbox table. */
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

    /** Takes every running grant away, removes the role and tells them. */
    record Revoke(DiscordId person) implements BotRequest {

        public Revoke {
            Objects.requireNonNull(person, "person");
        }
    }

    /**
     * Tells a payer what steward booked for them: the roles, the direct messages, the thank-you and the admin note.
     * The booking itself is done and committed with this row; the bot only reacts.
     *
     * @param payment       the request that was booked
     * @param reference     its {@code NT-XXXXXX}, which the admin note names
     * @param days          the days granted
     * @param donationCents what of the payment counts as a donation, zero for none
     * @param downgraded    whether fewer days were granted than ordered, because less money arrived
     * @param receivedCents what arrived, or {@code null} for an admin's booking by hand
     * @param from          when the access period it bought starts
     * @param until         when it ends
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
            Instant until)
            implements BotRequest {

        public PaymentBooked {
            Objects.requireNonNull(payment, "payment");
            Objects.requireNonNull(person, "person");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(until, "until");
            if (days <= 0) {
                throw new IllegalArgumentException("a booking grants at least one day, got " + days);
            }
        }

        /** Returns whether the payment earned the donor role. */
        public boolean donation() {
            return donationCents > 0;
        }
    }

    /** Breaks the link between a Discord account and a Minecraft one. */
    record Unlink(DiscordId person) implements BotRequest {

        public Unlink {
            Objects.requireNonNull(person, "person");
        }
    }

    /** Writes somebody's total play time. */
    record SetPlaytime(DiscordId person, long seconds) implements BotRequest {

        public SetPlaytime {
            Objects.requireNonNull(person, "person");
            if (seconds < 0) {
                throw new IllegalArgumentException("a play time is never negative, got " + seconds);
            }
        }
    }

    /**
     * Posts one alert into the admin channel, mentioning the admins who chose Discord for its type.
     *
     * @param link     the alert's page in Steward, or {@code null} for a request written before it had one
     * @param mentions empty for an all-clear, which tells without calling anybody
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
     * Posts one message per language into that language's announcement channel; a language without one is skipped.
     * The bot renders each in its language, so an override of the text reaches a request already written.
     *
     * @param messages language tag to the message of the database bundle's {@code announcement} section
     */
    record Announce(Map<String, MessageRef> messages) implements BotRequest {

        /** The bot's answer for a language whose text went out; the answer maps each language to this or the next. */
        public static final String POSTED = "POSTED";

        /** The bot's answer for a language without a channel it can write to. */
        public static final String NOT_POSTED = "NOT_POSTED";

        public Announce {
            messages = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(messages, "messages")));
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("an announcement carries at least one language");
            }
        }
    }

    /** Sends an admin a text they are trying as a direct message; refused when Discord does not deliver it. */
    record PreviewMessage(DiscordId person, MessagePreview preview) implements BotRequest {

        public PreviewMessage {
            Objects.requireNonNull(person, "person");
            Objects.requireNonNull(preview, "preview");
        }
    }
}
