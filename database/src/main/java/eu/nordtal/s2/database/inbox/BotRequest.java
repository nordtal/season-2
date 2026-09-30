package eu.nordtal.s2.database.inbox;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.notify.Channel;
import java.util.Objects;

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

    /** Books a payment by hand, named by its reference. */
    record Settle(String reference) implements BotRequest {

        public Settle {
            Objects.requireNonNull(reference, "reference");
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

    /** Re-reads every message bundle; {@code bundle} names the one whose override was written. */
    record ReloadMessages(String bundle) implements BotRequest {

        public ReloadMessages {
            Objects.requireNonNull(bundle, "bundle");
        }
    }
}
