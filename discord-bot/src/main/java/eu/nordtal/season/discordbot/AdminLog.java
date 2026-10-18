package eu.nordtal.season.discordbot;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static eu.nordtal.season.discordbot.AccessMessages.MESSAGES;

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
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.spec.layout.Element;
import eu.nordtal.season.messages.spec.layout.Layout;
import eu.nordtal.season.messages.spec.layout.Line;
import eu.nordtal.season.messages.spec.layout.Part;
import eu.nordtal.season.messages.value.Mention;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.jspecify.annotations.Nullable;

/**
 * Journals what members do and what the bot notes, posts each line to the admin channel, and raises the rest.
 *
 * An {@link #alert} is a row steward routes, so this class never decides who hears of it; {@link #postAlert} draws one.
 */
@Slf4j
public final class AdminLog {

    static final String RAISED_BY = "discord-bot";

    private static final String AREA = "Admin channel";

    /** A journal entry: its mark and action, its line, who did it, and whom and which account it concerns. */
    static final Layout JOURNAL = Layout.discord(
            "access",
            "admin-journal",
            AREA,
            List.of(
                    Element.list("concerns", "access/admin.concerns"),
                    Element.list("minecraft", "access/admin.minecraft")),
            Part.container(
                    Tone.BRAND,
                    Part.text(Line.title("access/admin.title")),
                    Part.text(Line.text("access/admin.line")),
                    Part.text(Line.text("access/admin.by"), Line.list("concerns"), Line.list("minecraft"))));

    /** An alert: the admins it calls above the box, so they are pinged, and its page's button below. */
    static final Layout ALERT = Layout.discord(
            "access",
            "admin-alert",
            AREA,
            List.of(
                    Element.list("mentions", "access/admin.mention"),
                    Element.list("lines", "access/admin.line"),
                    Element.button("open", "admin/run.open")),
            Part.text(Line.list("mentions")),
            Part.container(Tone.BRAND, Part.text(Line.title("access/admin.title")), Part.text(Line.list("lines"))),
            Part.row("open"));

    /** The mark of a journal line in the admin channel; an action not listed here is posted with a pencil. */
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
    private final LayoutRenderer layouts;

    public AdminLog(
            final JDA jda,
            final AccessSpec config,
            final AuditDirectory journal,
            final AlertBook alerts,
            final LayoutRenderer layouts) {
        this.jda = jda;
        this.config = config;
        this.journal = journal;
        this.alerts = alerts;
        this.layouts = layouts;
    }

    /** Raises something an admin must act on; steward routes it to push and back here, and this never throws. */
    public void alert(final Alert alert) {
        raise(alerts, alert);
    }

    static void raise(final AlertBook book, final Alert alert) {
        try {
            book.raise(alert, RAISED_BY);
        } catch (final RuntimeException exception) {
            // With the database gone, this line is the only place the alert exists.
            log.error("Could not raise the alert {}: {}", alert.title(), alert.detail(), exception);
        }
    }

    /** Posts an alert steward routed here, and answers whether there was an admin channel to post it to. */
    public boolean postAlert(final BotRequest.PostAlert alert) {
        return send(card(layouts, alert), alert.mentions());
    }

    /** Draws an alert: the admins it calls, its level's mark and title, the lines below it and its page's button. */
    static LayoutRenderer.Drawn card(final LayoutRenderer layouts, final BotRequest.PostAlert alert) {
        final String link = alert.link();
        return layouts.render(
                ALERT,
                LayoutRenderer.Filling.of(
                        Map.of(
                                "mentions",
                                new LayoutRenderer.FilledList(alert.mentions().stream()
                                        .map(admin -> MESSAGES.admin()
                                                .mention(Mention.of(admin))
                                                .args())
                                        .toList()),
                                "lines",
                                new LayoutRenderer.FilledList(alert.detail().stream()
                                        .map(line -> MESSAGES.admin().line(line).args())
                                        .toList()),
                                "open",
                                link == null
                                        ? new LayoutRenderer.FilledNothing()
                                        : new LayoutRenderer.FilledButton(link, ButtonStyle.LINK)),
                        MESSAGES.admin().title(mark(alert.level()).symbol(), alert.title())),
                Locales.DEFAULT);
    }

    /** Returns the mark of an alert's level. */
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
     * @param sentId called on a JDA thread with the message id, and not at all when the post fails
     */
    public void post(final LayoutRenderer.Drawn card, final Consumer<String> sentId) {
        final MessageChannel channel = channel();
        if (channel == null) {
            return;
        }
        channel.sendMessage(pinging(card, List.of()))
                .queue(
                        sent -> sentId.accept(sent.getId()),
                        failure -> log.error("Could not post a card to the admin channel", failure));
    }

    /** Rewrites a message this class posted; a failure is only logged, so it can never stop a run. */
    public void edit(final String messageId, final LayoutRenderer.Drawn card) {
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
     * Writes one journal entry and posts it to the admin channel, and never throws.
     *
     * The action it records has already happened. The card renders the same values the entry stores, so both agree.
     */
    public void record(final AuditLine line) {
        try {
            journal.record(line);
        } catch (final RuntimeException exception) {
            log.error("Could not write the journal entry for {} {}", line.action(), line.detail(), exception);
        }
        send(card(layouts, line), List.of());
    }

    /** Posts the card of a journal entry another service wrote, such as a booking's, drawn from its row. */
    public void show(final UUID entry) {
        journal.entry(entry)
                .ifPresentOrElse(
                        row -> send(
                                card(layouts, row.action(), row.actor(), row.subject(), row.mcUuid(), row.line()),
                                List.of()),
                        () -> log.error("Journal entry {} is gone, so its card was not posted", entry));
    }

    static LayoutRenderer.Drawn card(final LayoutRenderer layouts, final AuditLine line) {
        return card(layouts, line.action(), line.actor(), line.subject(), line.mcUuid(), line.line());
    }

    /** Draws a journal entry: its action as the title, its line, who did it and whom it concerns. */
    static LayoutRenderer.Drawn card(
            final LayoutRenderer layouts,
            final JournalAction action,
            final Actor actor,
            final @Nullable DiscordId subject,
            final @Nullable UUID account,
            final MessageRef line) {
        return layouts.render(
                JOURNAL,
                LayoutRenderer.Filling.of(
                        Map.of(
                                "concerns",
                                new LayoutRenderer.FilledList(
                                        subject == null
                                                ? List.of()
                                                : List.of(MESSAGES.admin()
                                                        .concerns(
                                                                TEXTS.journal().who(Actor.person(subject)))
                                                        .args())),
                                "minecraft",
                                new LayoutRenderer.FilledList(
                                        account == null
                                                ? List.of()
                                                : List.of(MESSAGES.admin()
                                                        .minecraft(account.toString())
                                                        .args()))),
                        MESSAGES.admin()
                                .title(
                                        MARKS.getOrDefault(action, Mark.NOTED).symbol(),
                                        TEXTS.journal().action(action)),
                        MESSAGES.admin().line(line),
                        MESSAGES.admin().by(TEXTS.journal().who(actor))),
                Locales.DEFAULT);
    }

    private @Nullable MessageChannel channel() {
        // Unconfigured and unresolvable log differently: no channel picked, or one since removed.
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

    /** Posts a card, pinging only {@code admins}: a mention elsewhere in a card names someone without a ping. */
    private boolean send(final LayoutRenderer.Drawn card, final List<DiscordId> admins) {
        final MessageChannel channel = channel();
        if (channel == null) {
            // At warn: with no admin channel this is the only place the message exists.
            log.warn("No admin channel, so this was not posted to Discord: {}", card.text());
            return false;
        }
        channel.sendMessage(pinging(card, admins))
                .queue(
                        success -> {},
                        failure -> log.error("Could not write to the admin channel: {}", card.text(), failure));
        return true;
    }

    static MessageCreateData pinging(final LayoutRenderer.Drawn card, final List<DiscordId> admins) {
        return MessageCreateBuilder.from(card.create())
                .setAllowedMentions(List.of())
                .mentionUsers(admins.stream().map(DiscordId::value).toList())
                .build();
    }
}
