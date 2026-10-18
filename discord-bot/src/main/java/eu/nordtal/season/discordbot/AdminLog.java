package eu.nordtal.season.discordbot;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertBook;
import eu.nordtal.season.database.audit.AuditDirectory;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.discordbot.config.AccessSpec;
import eu.nordtal.season.discordbot.config.Configured;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.spec.Takes;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.Mention;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.jspecify.annotations.Nullable;

@Slf4j
public final class AdminLog {

    static final String RAISED_BY = "discord-bot";

    private static final String AREA = "Admin channel";

    /**
     * @param concerns the member the entry is about, or {@code null}
     * @param minecraft the Minecraft account the entry is about, or {@code null}
     */
    record Journal(
            @Example(Mark.PREVIEW) String mark,

            @Takes({"admin/*", "database/*"}) @Example("admin/run.no-build")
            MessageRef title,

            @Takes({"admin/*", "database/*"}) @Example("admin/run.no-build")
            MessageRef line,

            @Takes({"admin/journal.actor", "admin/journal.person"})
            MessageRef by,

            @Takes({"admin/journal.actor", "admin/journal.person"}) @Nullable
            MessageRef concerns,

            @Nullable String minecraft) {}

    record Card(
            @Example(Mark.PREVIEW) String mark,

            @Takes({"admin/*", "database/*"}) @Example("admin/run.no-build")
            MessageRef title) {}

    record Pinged(Mention admin) {}

    record Detail(
            @Takes({"admin/*", "database/*"}) @Example("admin/run.no-build")
            MessageRef line) {}

    static final DocumentSpec<Journal> JOURNAL =
            DocumentSpec.message(StoredDocuments.NAMESPACE, "admin-journal", AREA, Journal.class);

    static final DocumentSpec<Card> ALERT = DocumentSpec.message(
            StoredDocuments.NAMESPACE,
            "admin-alert",
            AREA,
            Card.class,
            Slot.list("mentions", Pinged.class),
            Slot.list("lines", Detail.class),
            Slot.button("open").optional());

    private static final Map<JournalAction, Mark> MARKS = Map.of(
            JournalAction.GRANT_ACCESS, Mark.GRANTED,
            JournalAction.REVOKE_ACCESS, Mark.REVOKED,
            JournalAction.LINK, Mark.LINKED,
            JournalAction.UNLINK, Mark.UNLINKED,
            JournalAction.SET_PLAYTIME, Mark.TIMED,
            JournalAction.SETTLE, Mark.PAID,
            JournalAction.UNANCHORED_GRANT, Mark.WARNING,
            JournalAction.LINK_CODES_EXHAUSTED, Mark.LOCKED);

    private final JDA jda;
    private final AccessSpec config;
    private final AuditDirectory journal;
    private final AlertBook alerts;
    private final DocumentRenderer documents;

    public AdminLog(
            final JDA jda,
            final AccessSpec config,
            final AuditDirectory journal,
            final AlertBook alerts,
            final DocumentRenderer documents) {
        this.jda = jda;
        this.config = config;
        this.journal = journal;
        this.alerts = alerts;
        this.documents = documents;
    }

    /** Raises something an admin must act on. Steward routes it to push and back here. This method never throws. */
    public void alert(final Alert alert) {
        raise(alerts, alert);
    }

    static void raise(final AlertBook book, final Alert alert) {
        try {
            book.raise(alert, RAISED_BY);
        } catch (final RuntimeException exception) {
            log.error("Could not raise the alert {}: {}", alert.title(), alert.detail(), exception);
        }
    }

    public boolean postAlert(final BotRequest.PostAlert alert) {
        return send(card(documents, alert), alert.mentions());
    }

    static DocumentRenderer.Drawn card(final DocumentRenderer documents, final BotRequest.PostAlert alert) {
        final String link = alert.link();
        return documents.render(
                ALERT,
                new Card(mark(alert.level()).symbol(), alert.title()),
                Map.of(
                        "mentions",
                        DocumentRenderer.FilledList.of(alert.mentions().stream()
                                .map(admin -> new Pinged(Mention.of(admin)))
                                .toList()),
                        "lines",
                        DocumentRenderer.FilledList.of(
                                alert.detail().stream().map(Detail::new).toList()),
                        "open",
                        link == null ? new DocumentRenderer.FilledNothing() : DocumentRenderer.FilledButton.link(link)),
                Locales.DEFAULT);
    }

    static Mark mark(final Alert.Level level) {
        return switch (level) {
            case DOWN -> Mark.FAILED;
            case WARN -> Mark.WARNING;
            case OK -> Mark.DONE;
        };
    }

    /**
     * Posts a card that a caller can rewrite later, and pings nobody.
     *
     * @param sentId runs on a JDA thread with the message id, and not at all when the post fails
     */
    public void post(final DocumentRenderer.Drawn card, final Consumer<String> sentId) {
        final MessageChannel channel = channel();
        if (channel == null) {
            return;
        }
        channel.sendMessage(pinging(card, List.of()))
                .queue(
                        sent -> sentId.accept(sent.getId()),
                        failure -> log.error("Could not post a card to the admin channel", failure));
    }

    public void edit(final String messageId, final DocumentRenderer.Drawn card) {
        final MessageChannel channel = channel();
        if (channel == null) {
            return;
        }
        channel.editMessageById(messageId, card.edit())
                .queue(
                        success -> {},
                        failure -> log.error("Could not edit admin-channel message {}", messageId, failure));
    }

    /**
     * Writes one journal entry, posts it to the admin channel and never throws.
     * The action has already happened. The card renders the values the entry stores, so both agree.
     */
    public void record(final AuditLine line) {
        try {
            journal.record(line);
        } catch (final RuntimeException exception) {
            log.error("Could not write the journal entry for {} {}", line.action(), line.detail(), exception);
        }
        send(card(documents, line), List.of());
    }

    public void show(final UUID entry) {
        journal.entry(entry)
                .ifPresentOrElse(
                        row -> send(
                                card(documents, row.action(), row.actor(), row.subject(), row.mcUuid(), row.line()),
                                List.of()),
                        () -> log.error("Journal entry {} is gone, so its card was not posted", entry));
    }

    static DocumentRenderer.Drawn card(final DocumentRenderer documents, final AuditLine line) {
        return card(documents, line.action(), line.actor(), line.subject(), line.mcUuid(), line.line());
    }

    static DocumentRenderer.Drawn card(
            final DocumentRenderer documents,
            final JournalAction action,
            final Actor actor,
            final @Nullable DiscordId subject,
            final @Nullable UUID account,
            final MessageRef line) {
        return documents.render(
                JOURNAL,
                new Journal(
                        MARKS.getOrDefault(action, Mark.NOTED).symbol(),
                        TEXTS.journal().action(action),
                        line,
                        TEXTS.journal().who(actor),
                        subject == null ? null : TEXTS.journal().who(Actor.person(subject)),
                        account == null ? null : account.toString()),
                Map.of(),
                Locales.DEFAULT);
    }

    private @Nullable MessageChannel channel() {
        if (!Configured.isSet(config.channels().admin())) {
            return null;
        }
        final MessageChannel channel =
                jda.getChannelById(MessageChannel.class, config.channels().admin());
        if (channel == null) {
            log.error(
                    "Admin channel {} does not exist or the bot cannot see it",
                    config.channels().admin());
        }
        return channel;
    }

    private boolean send(final DocumentRenderer.Drawn card, final List<DiscordId> admins) {
        final MessageChannel channel = channel();
        if (channel == null) {
            log.warn("No admin channel, so this was not posted to Discord: {}", card.text());
            return false;
        }
        channel.sendMessage(pinging(card, admins))
                .queue(
                        success -> {},
                        failure -> log.error("Could not write to the admin channel: {}", card.text(), failure));
        return true;
    }

    static MessageCreateData pinging(final DocumentRenderer.Drawn card, final List<DiscordId> admins) {
        return MessageCreateBuilder.from(card.create())
                .setAllowedMentions(List.of())
                .mentionUsers(admins.stream().map(DiscordId::value).toList())
                .build();
    }
}
