package eu.nordtal.s2.database;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.database.alert.AlertChannel;
import eu.nordtal.s2.database.alert.AlertType;
import eu.nordtal.s2.database.audit.JournalAction;
import eu.nordtal.s2.database.payment.PaymentMatch;
import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateStatus;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.Display;
import eu.nordtal.s2.messages.spec.MessageSpec;
import eu.nordtal.s2.messages.spec.MessageSpecs;
import eu.nordtal.s2.messages.spec.Name;
import eu.nordtal.s2.messages.spec.TextFormat;
import eu.nordtal.s2.messages.value.Mention;
import eu.nordtal.s2.messages.value.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * What an admin reads of the network, English only: the journal, on Steward's page and in the admin channel.
 * Written for Discord, whose target escapes each value; a text here carries no markdown, so the page shows it as is.
 */
@MessageSpec(value = "admin", format = TextFormat.DISCORD_MARKDOWN, shown = Display.DISCORD_EMBED)
public interface AdminTexts {

    /** The texts; stateless, so one instance serves every caller. */
    AdminTexts TEXTS = MessageSpecs.create(AdminTexts.class);

    Journal journal();

    Run run();

    /**
     * One line per action, its values typed; the row stores the message, and each reader's target renders it.
     *
     * A value keeps the name the old facts gave it, so a row the migration carried over renders as one written today.
     */
    @Name("Journal")
    interface Journal {

        @Name("Action")
        MessageRef action(@Arg("action") JournalAction action);

        @Name("Who did it, when nobody pressed anything")
        MessageRef actor(@Arg("kind") Actor.Kind kind);

        @Name("Who did it, a person")
        MessageRef person(@Arg("person") Mention person);

        @Name("By, as a heading")
        MessageRef by();

        @Name("Minecraft account, as a heading")
        MessageRef minecraft();

        @Name("Concerns, as a heading")
        MessageRef concerns();

        @Name("A line from before typed values")
        MessageRef written(@Arg("detail") String detail);

        @Name("First admin")
        MessageRef adminRoot();

        @Name("Admin granted")
        MessageRef grantAdmin();

        @Name("Admin revoked")
        MessageRef revokeAdmin(@Arg("below") List<String> below, @Arg("count") int count);

        @Name("Pack enforced")
        MessageRef enforcePack();

        @Name("Pack exempted")
        MessageRef exemptPack();

        @Name("Access granted")
        MessageRef grantAccess(@Arg("days") int days, @Arg("until") Instant until);

        @Name("Access revoked")
        MessageRef revokeAccess(@Arg("grants") int grants);

        @Name("Play time set")
        MessageRef setPlaytime(@Arg("seconds") Duration seconds);

        @Name("Account linked")
        MessageRef link();

        @Name("Account unlinked")
        MessageRef unlink(@Arg("selfService") boolean selfService);

        @Name("Payment settled")
        MessageRef settle(
                @Arg("reference") String reference,
                @Arg("matched") PaymentMatch matched,
                @Arg("days") int days,
                @Arg("ordered") int ordered,
                @Arg("received") Money received,
                @Arg("donation") Money donation);

        @Name("Payment settled by hand")
        MessageRef settleByHand(
                @Arg("reference") String reference,
                @Arg("days") int days,
                @Arg("ordered") int ordered,
                @Arg("donation") Money donation);

        @Name("Phase changed")
        MessageRef setPhase(@Arg("from") SeasonPhase from, @Arg("to") SeasonPhase to);

        @Name("Phase changed, with a reason")
        MessageRef setPhaseBecause(
                @Arg("from") SeasonPhase from, @Arg("to") SeasonPhase to, @Arg("reason") String reason);

        @Name("Opening set")
        MessageRef setLaunch(@Arg("to") Instant to);

        @Name("Opening cleared")
        MessageRef clearLaunch();

        @Name("Paid time set")
        MessageRef setSmpStart(@Arg("to") Instant to, @Arg("movedGrants") long movedGrants);

        @Name("Paid time cleared")
        MessageRef clearSmpStart();

        @Name("Security key added")
        MessageRef registerKey(@Arg("label") String label);

        @Name("Security key used")
        MessageRef heldKey(@Arg("label") String label, @Arg("unlocked") boolean unlocked);

        @Name("Security key renamed")
        MessageRef renameKey(@Arg("label") String label);

        @Name("Security key removed")
        MessageRef removeKey(@Arg("label") String label, @Arg("left") int left);

        @Name("Security reset")
        MessageRef forgetFactors(@Arg("keys") int keys, @Arg("sessions") int sessions);

        @Name("Push switched on")
        MessageRef webPushSubscribe();

        @Name("Push switched off")
        MessageRef webPushUnsubscribe();

        @Name("Push tested")
        MessageRef webPushTest(@Arg("alert") AlertType alert);

        @Name("Alert preference")
        MessageRef setAlertPreference(
                @Arg("alert") AlertType alert, @Arg("channel") AlertChannel channel, @Arg("enabled") boolean enabled);

        @Name("Console line")
        MessageRef console(@Arg("service") String service, @Arg("command") String command);

        @Name("Settings saved")
        MessageRef saveSettings(@Arg("service") String service, @Arg("group") String group);

        @Name("Texts saved")
        MessageRef saveMessages(@Arg("bundle") String bundle);

        @Name("Plugin added")
        MessageRef addPlugin(@Arg("service") String service, @Arg("artifact") String artifact);

        @Name("Run cancelled")
        MessageRef cancelRun(@Arg("run") long run, @Arg("kind") UpdateKind kind);

        @Name("Announcement")
        MessageRef announce(@Arg("languages") List<String> languages);

        @Name("Objective completed")
        MessageRef completeObjective(@Arg("objective") String objective);

        @Name("Milestone unlocked")
        MessageRef unlockMilestone(@Arg("milestone") String milestone);

        @Name("Hunger Games started")
        MessageRef startGame();
    }

    /** A run, as the journal, Steward's feed and the bot's update feed name it. */
    @Name("Runs")
    interface Run {

        @Name("Run kind")
        MessageRef kind(@Arg("kind") UpdateKind kind);

        @Name("Run status")
        MessageRef status(@Arg("status") UpdateStatus status);

        @Name("Run stage")
        MessageRef stage(@Arg("stage") UpdateReport.Stage stage);

        @Name("Services that came back")
        MessageRef successful(@Arg("successful") long successful, @Arg("total") long total);
    }
}
