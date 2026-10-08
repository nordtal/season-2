package eu.nordtal.season.database;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertChannel;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.alert.DiscordRole;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.payment.PaymentMatch;
import eu.nordtal.season.database.payment.PaymentRequestStatus;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.Formerly;
import eu.nordtal.season.messages.spec.MessageSpec;
import eu.nordtal.season.messages.spec.MessageSpecs;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.spec.Shown;
import eu.nordtal.season.messages.spec.TextFormat;
import eu.nordtal.season.messages.value.Mention;
import eu.nordtal.season.messages.value.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * What an admin reads of the network, English only: the journal, the runs and their reports, the alerts and the notes.
 * Written for Discord, whose target escapes each value; a text here carries no markdown, so the page shows it as is.
 */
@MessageSpec(value = "admin", format = TextFormat.DISCORD_MARKDOWN)
public interface AdminTexts {

    /** The texts; stateless, so one instance serves every caller. */
    AdminTexts TEXTS = MessageSpecs.create(AdminTexts.class);

    Journal journal();

    Run run();

    Alerts alert();

    Notes note();

    @Shown({Display.STEWARD, Display.DISCORD_EMBED})
    ReportTexts report();

    /**
     * One line per action, its values typed; the row stores the message, and each reader's target renders it.
     *
     * A value is named after the fact it states.
     */
    @Name("Journal")
    @Shown(Display.STEWARD)
    interface Journal {

        @Name("Action")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED_HEADING})
        MessageRef action(@Arg("action") JournalAction action);

        @Name("Who did it, when nobody pressed anything")
        @Shown(Display.DISCORD_EMBED)
        MessageRef actor(@Arg("kind") Actor.Kind kind);

        @Name("Who did it, a person")
        @Shown(Display.DISCORD_EMBED)
        MessageRef person(@Arg("person") Mention person);

        /** Who did it: a person as their mention, anyone else as the word for their kind. */
        default MessageRef who(final Actor actor) {
            final DiscordId person = actor.person();
            return person == null ? actor(actor.kind()) : person(Mention.of(person));
        }

        @Name("By, as a heading")
        @Shown(Display.DISCORD_EMBED_HEADING)
        MessageRef by();

        @Name("Minecraft account, as a heading")
        @Shown(Display.DISCORD_EMBED_HEADING)
        MessageRef minecraft();

        @Name("Concerns, as a heading")
        @Shown(Display.DISCORD_EMBED_HEADING)
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
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef grantAccess(@Arg("days") int days, @Arg("until") Instant until);

        @Name("Access revoked")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef revokeAccess(@Arg("grants") int grants);

        @Name("Play time set")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef setPlaytime(@Arg("seconds") Duration seconds);

        @Name("Account linked")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef link();

        @Name("Account unlinked")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
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

        @Name("Backup downloaded")
        MessageRef downloadBackup(@Arg("archive") String archive, @Arg("size") MessageRef size);

        @Name("Backup downloaded, size unknown")
        MessageRef downloadBackupUnsized(@Arg("archive") String archive);

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
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef kind(@Arg("kind") UpdateKind kind);

        @Name("Run status")
        @Shown(Display.STEWARD)
        MessageRef status(@Arg("status") UpdateStatus status);

        @Name("Run stage")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED_HEADING})
        MessageRef stage(@Arg("stage") UpdateReport.Stage stage);

        @Name("Services that came back")
        @Shown(Display.STEWARD)
        MessageRef successful(@Arg("successful") long successful, @Arg("total") long total);

        @Name("A service's state in a run")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef state(@Arg("state") UpdateReport.State state);

        @Name("Run, as a heading")
        @Shown(Display.DISCORD_EMBED_HEADING)
        MessageRef heading();

        @Name("Services, as a heading")
        @Shown(Display.DISCORD_EMBED_HEADING)
        MessageRef services();

        @Name("What explains a failed run, as a heading")
        @Shown(Display.DISCORD_EMBED_HEADING)
        @Formerly("run.notes")
        MessageRef why();

        @Name("The part of a run a note is about")
        @Shown(Display.STEWARD)
        MessageRef step(@Arg("step") UpdateReport.Step step);

        @Name("How a note went")
        @Shown(Display.STEWARD)
        MessageRef outcome(@Arg("outcome") UpdateReport.Outcome outcome);

        @Name("Duration, as a heading")
        @Shown(Display.DISCORD_EMBED_HEADING)
        MessageRef duration();

        @Name("How long a run took")
        @Shown(Display.DISCORD_EMBED)
        MessageRef took(@Arg("took") Duration took);

        @Name("An artefact with no build for this Minecraft version")
        @Shown(Display.DISCORD_EMBED)
        MessageRef noBuild();

        @Name("Lines left out")
        @Shown(Display.DISCORD_EMBED)
        MessageRef more(@Arg("count") int count);
    }

    /** An alert: its title and the lines below it, which a lock screen, the admin channel and the page render. */
    @Name("Alerts")
    @Shown({Display.STEWARD, Display.PUSH, Display.DISCORD_EMBED_HEADING})
    interface Alerts {

        @Name("Level")
        @Shown({Display.STEWARD, Display.PUSH})
        MessageRef level(@Arg("level") Alert.Level level);

        @Name("Words as they came: a program's answer, or a line from before typed alerts")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef words(@Arg("text") String text);

        @Name("All clear")
        MessageRef clear(@Arg("type") AlertType type);

        @Name("Several of one type at once")
        MessageRef several(@Arg("subjects") List<String> subjects, @Arg("count") int count);

        @Name("A run failed")
        MessageRef runFailed(@Arg("kind") UpdateKind kind);

        @Name("Which run")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef run(@Arg("run") long run);

        @Name("No services")
        MessageRef noServices();

        @Name("A service is not running")
        MessageRef notRunning(@Arg("service") String service);

        @Name("A service is unhealthy")
        MessageRef unhealthy(@Arg("service") String service);

        @Name("Failing healthcheck")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef healthFails();

        @Name("Docker's word")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef dockerState(@Arg("state") String state);

        @Name("A one-shot service failed")
        MessageRef failedOnce(@Arg("service") String service, @Arg("code") int code);

        @Name("Older images")
        MessageRef olderImage(@Arg("services") List<String> services, @Arg("count") int count);

        @Name("Images not compared")
        MessageRef notCompared();

        @Name("No finished backup")
        MessageRef noBackup();

        @Name("Only started backups")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef onlyStarted();

        @Name("No database dump")
        MessageRef noDump();

        @Name("What a missing dump means")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef dumpMatters();

        @Name("No archive of a volume")
        MessageRef noArchive(@Arg("volume") String volume);

        @Name("An old dump")
        MessageRef oldDump(@Arg("hours") long hours);

        @Name("An old archive")
        MessageRef oldArchive(@Arg("volume") String volume, @Arg("hours") long hours);

        @Name("No copy off the host")
        MessageRef noOffsite();

        @Name("An old copy off the host")
        MessageRef oldOffsite(@Arg("hours") long hours);

        @Name("The permitted age")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef permittedAge(@Arg("hours") long hours);

        @Name("A full disk")
        MessageRef disk(@Arg("percent") long percent);

        @Name("Used memory")
        MessageRef memory(@Arg("percent") long percent);

        @Name("The threshold")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef threshold(@Arg("percent") long percent);

        @Name("No memory limit")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef noLimit();

        @Name("A payment needs a look")
        MessageRef payment();

        @Name("An unknown reference")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef unknownReference(
                @Arg("payment") String payment, @Arg("amount") Money amount, @Arg("reference") String reference);

        @Name("A request no longer open")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef notOpen(
                @Arg("payment") String payment,
                @Arg("amount") Money amount,
                @Arg("reference") String reference,
                @Arg("status") PaymentRequestStatus status);

        @Name("A payment claimed twice")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef claimed(
                @Arg("payment") String payment, @Arg("amount") Money amount, @Arg("reference") String reference);

        @Name("A payment below every tier")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef noTier(
                @Arg("payment") String payment, @Arg("amount") Money amount, @Arg("reference") String reference);

        @Name("Several roles of one name")
        MessageRef roleAmbiguous(@Arg("name") String name);

        @Name("None of them taken")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef noneAdopted();

        @Name("A role not created")
        MessageRef roleNotCreated(@Arg("name") String name);

        @Name("The lock without its channel")
        MessageRef lockWithoutChannel();

        @Name("Nobody locked")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef nobodyLocked();

        @Name("Channels the lock does not close")
        MessageRef lockNotKept(@Arg("count") int count);

        @Name("Which channel, and what went wrong")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef failedIn(@Arg("channel") String channel, @Arg("error") String error);

        @Name("A role not changed")
        MessageRef roleNotChanged(@Arg("role") DiscordRole role, @Arg("given") boolean given);

        @Name("Who, and what went wrong")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef failedFor(@Arg("person") Mention person, @Arg("error") String error);

        @Name("A direct message not delivered")
        MessageRef dm();

        @Name("To whom")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef to(@Arg("person") Mention person);

        @Name("A link failed")
        MessageRef linkFailed();

        @Name("A purchase failed")
        MessageRef purchaseFailed();

        @Name("bunq refused a payment link")
        MessageRef linkRefused();

        @Name("Which request, and what bunq said")
        @Shown({Display.STEWARD, Display.DISCORD_EMBED})
        MessageRef refused(@Arg("reference") String reference, @Arg("error") String error);
    }

    /** A card the bot posts to the admin channel for later, without a mention. */
    @Name("Notes")
    @Shown(Display.DISCORD_EMBED_HEADING)
    interface Notes {

        @Name("Payment booked")
        MessageRef paymentBooked();

        @Name("What a booked payment bought")
        @Shown(Display.DISCORD_EMBED)
        MessageRef booked(
                @Arg("reference") String reference,
                @Arg("payer") Mention payer,
                @Arg("days") int days,
                @Arg("until") Instant until);

        @Name("Too many wrong link codes")
        MessageRef tooManyCodes();

        @Name("Refused linking for the hour")
        @Shown(Display.DISCORD_EMBED)
        MessageRef refusedForTheHour(@Arg("person") Mention person);

        @Name("No season start")
        MessageRef noSeasonStart();

        @Name("Access anchored to the grant")
        @Shown(Display.DISCORD_EMBED)
        MessageRef runsFromTheGrant(@Arg("person") Mention person, @Arg("from") Instant from);
    }
}
