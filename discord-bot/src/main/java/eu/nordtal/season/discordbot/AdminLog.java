package eu.nordtal.season.discordbot;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertBook;
import eu.nordtal.season.database.alert.RaisedAlert;
import eu.nordtal.season.database.audit.AuditDirectory;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.discordbot.config.AccessSpec;
import eu.nordtal.season.discordbot.config.Configured;
import eu.nordtal.season.discordrendering.DocumentRenderer;
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
import java.util.function.LongFunction;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.jspecify.annotations.Nullable;

@Slf4j
public final class AdminLog {

    private static final String AREA = "Admin channel";

    /**
     * @param concerns the member the entry is about, or {@code null}
     * @param minecraft the Minecraft account the entry is about, or {@code null}
     */
    record Journal(
            @Example(Mark.PREVIEW) String mark,

            @Takes({"admin/*", "database/*"}) @Example("admin/journal.action")
            MessageRef title,

            @Takes({"admin/*", "database/*"}) @Example("admin/journal.admin-root")
            MessageRef line,

            @Takes({"admin/journal.actor", "admin/journal.person"})
            MessageRef by,

            @Takes({"admin/journal.actor", "admin/journal.person"}) @Nullable
            MessageRef concerns,

            @Nullable @Example("Red_Fox") String minecraft) {}

    record Card(
            @Example(Mark.PREVIEW) String mark,

            @Takes({"admin/*", "database/*"}) @Example("admin/alert.not-running")
            MessageRef title) {}

    record Pinged(@Example("Kim") Mention admin) {}

    record Detail(
            @Takes({"admin/*", "database/*"}) @Example("admin/alert.restarted-by-docker")
            MessageRef line) {}

    static final DocumentSpec<Journal> JOURNAL = DocumentSpec.message(
                    StoredDocuments.NAMESPACE, "admin-journal", AREA, Journal.class)
            .titled("Journal entry");

    static final DocumentSpec<Card> ALERT = DocumentSpec.message(
                    StoredDocuments.NAMESPACE,
                    "admin-alert",
                    AREA,
                    Card.class,
                    Slot.list("mentions", Pinged.class),
                    Slot.list("lines", Detail.class),
                    Slot.button("open").optional())
            .titled("Alert");

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
    private final String stewardUrl;

    /** @param stewardUrl Steward's address, which an alert card's link starts with. An empty value shows no link. */
    public AdminLog(
            final JDA jda,
            final AccessSpec config,
            final AuditDirectory journal,
            final AlertBook alerts,
            final DocumentRenderer documents,
            final String stewardUrl) {
        this.jda = jda;
        this.config = config;
        this.journal = journal;
        this.alerts = alerts;
        this.documents = documents;
        this.stewardUrl = stewardUrl.endsWith("/") ? stewardUrl.substring(0, stewardUrl.length() - 1) : stewardUrl;
    }

    /** Raises something an admin must act on. Steward routes it to push and back here. This method never throws. */
    public void alert(final Alert alert) {
        raise(alerts, alert);
    }

    static void raise(final AlertBook book, final Alert alert) {
        try {
            book.raise(alert, Actor.BOT);
        } catch (final RuntimeException exception) {
            log.error("Could not raise the alert {}: {}", alert.action(), alert.detail(), exception);
        }
    }

    /**
     * Posts the alert the request names in place of the card of the failed run it reports, or as its answer.
     * Returns false when there is nothing to post or no channel.
     *
     * @param role the admin role's id, or {@code null} while the guild has none
     * @param runCards hands over the card of a failed run once, or {@code null}
     */
    public boolean postAlert(
            final BotRequest.PostAlert request,
            final @Nullable String role,
            final LongFunction<@Nullable String> runCards) {
        final RaisedAlert raised = alerts.alert(request.entry()).orElse(null);
        if (raised == null) {
            log.warn("There is no alert {} to post", request.entry());
            return false;
        }
        final Alert alert = raised.alert();
        final String link = stewardUrl.isBlank() ? null : stewardUrl + alert.path();
        final String pinged = request.mention() ? role : null;
        final DocumentRenderer.Drawn card = card(documents, alert, pinged, link);
        final String runCard =
                alert.detail() instanceof final JournalDetail.RunFailed failed ? runCards.apply(failed.run()) : null;
        final MessageChannel channel = channel();
        if (runCard == null || channel == null) {
            return send(card, pinged);
        }
        channel.deleteMessageById(runCard).queue(gone -> send(card, pinged), failure -> {
            log.warn("Could not delete the run card {}, so the alert answers it", runCard, failure);
            channel.sendMessage(pinging(card, pinged))
                    .setMessageReference(runCard)
                    .queue(
                            success -> {},
                            unsent -> log.error("Could not write to the admin channel: {}", card.text(), unsent));
        });
        return true;
    }

    /** @param role the role the card mentions, or {@code null} for none */
    static DocumentRenderer.Drawn card(
            final DocumentRenderer documents,
            final Alert alert,
            final @Nullable String role,
            final @Nullable String link) {
        return documents.render(
                ALERT,
                new Card(mark(alert.level()).symbol(), alert.title()),
                Map.of(
                        "mentions",
                        DocumentRenderer.FilledList.of(
                                role == null ? List.of() : List.of(new Pinged(Mention.role(DiscordId.of(role))))),
                        "lines",
                        DocumentRenderer.FilledList.of(
                                alert.lines().stream().map(Detail::new).toList()),
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
        channel.sendMessage(pinging(card, null))
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
        send(card(documents, line), null);
    }

    public void show(final UUID entry) {
        journal.entry(entry)
                .ifPresentOrElse(
                        row -> send(
                                card(documents, row.action(), row.actor(), row.subject(), row.mcUuid(), row.line()),
                                null),
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

    private boolean send(final DocumentRenderer.Drawn card, final @Nullable String role) {
        final MessageChannel channel = channel();
        if (channel == null) {
            log.warn("No admin channel, so this was not posted to Discord: {}", card.text());
            return false;
        }
        channel.sendMessage(pinging(card, role))
                .queue(
                        success -> {},
                        failure -> log.error("Could not write to the admin channel: {}", card.text(), failure));
        return true;
    }

    /** Returns the card's message, which notifies only {@code role}, or nobody when it is {@code null}. */
    static MessageCreateData pinging(final DocumentRenderer.Drawn card, final @Nullable String role) {
        return MessageCreateBuilder.from(card.create())
                .setAllowedMentions(List.of())
                .mentionRoles(role == null ? List.of() : List.of(role))
                .build();
    }
}
