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
import eu.nordtal.season.database.payment.TabRefusal;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.MessageSpec;
import eu.nordtal.season.messages.spec.MessageSpecs;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.spec.Shown;
import eu.nordtal.season.messages.spec.Takes;
import eu.nordtal.season.messages.spec.TextFormat;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.Mention;
import eu.nordtal.season.messages.value.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * What an admin reads of the network, in English: the journal, the runs and their reports and the alerts.
 * The Discord target escapes each value, so a text here has no markdown.
 */
@MessageSpec(value = "admin", format = TextFormat.DISCORD_MARKDOWN)
public interface AdminTexts {

    /** The texts. */
    AdminTexts TEXTS = MessageSpecs.create(AdminTexts.class);

    Journal journal();

    Run run();

    Alerts alert();

    @Shown({Display.STEWARD, Display.DISCORD_TEXT})
    ReportTexts report();

    /** One line per action, rendered from the values of its journal entry. */
    @Name("Journal")
    @Shown(Display.STEWARD)
    interface Journal {

        @Name("Action")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef action(@Arg("action") JournalAction action);

        @Name("Who did it, when nobody pressed anything")
        @Shown(Display.DISCORD_TEXT)
        MessageRef actor(@Arg("kind") @Example("steward") Actor.Kind kind);

        @Name("Who did it, a person")
        @Shown(Display.DISCORD_TEXT)
        MessageRef person(@Arg("person") Mention person);

        /** Who did it: a person as a mention, anyone else as the word for their kind. */
        default MessageRef who(final Actor actor) {
            final DiscordId person = actor.person();
            return person == null ? actor(actor.kind()) : person(Mention.of(person));
        }

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
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef grantAccess(@Arg("days") int days, @Arg("until") Instant until);

        @Name("Access revoked")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef revokeAccess(@Arg("grants") int grants);

        @Name("Play time set")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef setPlaytime(@Arg("seconds") Duration seconds);

        @Name("Account linked")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef link();

        @Name("Account unlinked")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
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
        MessageRef heldKeyFor(
                @Arg("label") String label, @Arg("unlocked") boolean unlocked, @Arg("hold") Duration hold);

        @Name("Security key renamed")
        MessageRef renameKey(@Arg("label") String label);

        @Name("Security key removed")
        MessageRef removeKey(@Arg("label") String label, @Arg("left") int left);

        @Name("Authenticator app added")
        MessageRef addAuthenticator();

        @Name("Authenticator app used")
        MessageRef usedAuthenticator(@Arg("hold") Duration hold);

        @Name("Authenticator app removed")
        MessageRef removeAuthenticator(@Arg("keys") int keys);

        @Name("Security reset")
        MessageRef forgetEveryFactor(
                @Arg("keys") int keys, @Arg("authenticator") boolean authenticator, @Arg("sessions") int sessions);

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

        @Name("Plugin removed")
        MessageRef removePlugin(@Arg("run") long run, @Arg("service") String service, @Arg("artifact") String artifact);

        @Name("Run asked for")
        MessageRef requestRun(
                @Arg("run") long run,
                @Arg("kind") UpdateKind kind,
                @Arg("services") List<String> services,
                @Arg("count") int count);

        @Name("Restore asked for")
        MessageRef requestRestore(@Arg("run") long run, @Arg("archive") String archive);

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

        @Name("Access anchored to the grant")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef unanchoredGrant(@Arg("from") Instant from);

        @Name("Refused linking for the hour")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef linkCodesExhausted();
    }

    /** A run as the journal, the feed and the bot name it. */
    @Name("Runs")
    interface Run {

        @Name("Run kind")
        @Shown(Display.STEWARD)
        MessageRef kind(@Arg("kind") UpdateKind kind);

        @Name("Run status")
        @Shown(Display.STEWARD)
        MessageRef status(@Arg("status") UpdateStatus status);

        /** A run named in words: the title of its card and of its failed alert, and its row in the feed. */
        @Name("A run's kind and stage, as a title")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT, Display.PUSH})
        MessageRef title(@Arg("kind") UpdateKind kind, @Arg("stage") UpdateReport.Stage stage);

        @Name("Services that came back")
        @Shown(Display.STEWARD)
        MessageRef successful(@Arg("successful") long successful, @Arg("total") long total);

        @Name("A service's state in a run")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef state(@Arg("state") UpdateReport.State state);

        @Name("The part of a run a note is about")
        @Shown(Display.STEWARD)
        MessageRef step(@Arg("step") UpdateReport.Step step);

        @Name("How a step went")
        @Shown(Display.STEWARD)
        MessageRef outcome(@Arg("outcome") RunStep.Outcome outcome);

        @Name("What a step does")
        @Shown(Display.STEWARD)
        MessageRef stepKind(@Arg("kind") RunStep.Kind kind);

        @Name("Why a step did not go as asked")
        @Shown(Display.STEWARD)
        MessageRef reason(@Arg("reason") RunStep.Reason reason);

        @Name("The exit code of a tool that failed")
        @Shown(Display.STEWARD)
        MessageRef exitCode(@Arg("code") @Example("2") long code);

        @Name("Which image a container was recreated from")
        @Shown(Display.STEWARD)
        MessageRef image(@Arg("image") RunStep.Image image);

        @Name("Players moved off a service and back")
        @Shown(Display.STEWARD)
        MessageRef players(
                @Arg("moved") long moved,
                @Arg("from") @Example("smp") String from,
                @Arg("to") @Example("limbo") String to,
                @Arg("returned") long returned);

        @Name("Images a cleanup removed")
        @Shown(Display.STEWARD)
        MessageRef imagesRemoved(@Arg("images") long images, @Arg("freed") MessageRef freed);

        @Name("The build cache a cleanup freed")
        @Shown(Display.STEWARD)
        MessageRef cacheFreed(@Arg("freed") MessageRef freed);

        @Name("The room a backup needs and finds")
        @Shown(Display.STEWARD)
        MessageRef room(@Arg("needed") MessageRef needed, @Arg("free") MessageRef free);

        @Name("An offsite copy")
        @Shown(Display.STEWARD)
        MessageRef offsite(@Arg("snapshot") @Example("4f1c2d") String snapshot, @Arg("added") MessageRef added);

        @Name("An archive a step saved")
        @Shown(Display.STEWARD)
        MessageRef archiveSaved(
                @Arg("file") @Example("nordtal-20261020-0400.dump") String file,
                @Arg("size") MessageRef size,
                @Arg("took") Duration took,
                @Arg("verified") boolean verified);

        @Name("An archive a step pruned")
        @Shown(Display.STEWARD)
        MessageRef archivePruned(
                @Arg("file") @Example("nordtal-20261020-0400.dump") String file, @Arg("rule") RunStep.PruneRule rule);

        @Name("An artefact with no build for this Minecraft version")
        @Shown(Display.DISCORD_TEXT)
        MessageRef noBuild();

        @Name("What changed of a service")
        @Shown(Display.DISCORD_TEXT)
        MessageRef change(
                @Arg("artefact") @Example("paper") String artefact,
                @Arg("change") @Takes({"admin/*", "database/*"}) @Example("admin/run.no-build") MessageRef change);

        @Name("A version replaced by another")
        @Shown(Display.DISCORD_TEXT)
        MessageRef moved(@Arg("from") @Example("1.21.4") String from, @Arg("to") @Example("1.21.5") String to);

        @Name("A version")
        @Shown(Display.DISCORD_TEXT)
        MessageRef version(@Arg("to") @Example("1.21.5") String to);
    }

    /** An alert: its title and the lines below it. */
    @Name("Alerts")
    @Shown({Display.STEWARD, Display.PUSH, Display.DISCORD_TEXT})
    interface Alerts {

        @Name("Level")
        @Shown({Display.STEWARD, Display.PUSH})
        MessageRef level(@Arg("level") Alert.Level level);

        @Name("Words as they came: a program's answer, or a line from before typed alerts")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef words(@Arg("text") String text);

        @Name("All clear")
        MessageRef clear(@Arg("type") AlertType type);

        @Name("Several of one type at once")
        MessageRef several(@Arg("subjects") List<String> subjects, @Arg("count") int count);

        @Name("Which run")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef run(@Arg("run") long run);

        @Name("Why a run failed, about one service")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef failure(@Arg("service") @Example("smp") String service, @Arg("what") MessageRef what);

        @Name("No services")
        MessageRef noServices();

        @Name("A service is not running")
        MessageRef notRunning(@Arg("service") @Example("smp") String service);

        @Name("A service is unhealthy")
        MessageRef unhealthy(@Arg("service") @Example("smp") String service);

        @Name("Failing healthcheck")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef healthFails();

        @Name("Docker's word")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef dockerState(@Arg("state") String state);

        @Name("A service restarts")
        MessageRef restarting(@Arg("service") @Example("smp") String service, @Arg("count") int count);

        @Name("Restarted by Docker")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef restartedByDocker();

        @Name("A one-shot service failed")
        MessageRef failedOnce(@Arg("service") @Example("smp") String service, @Arg("code") int code);

        @Name("Older images")
        MessageRef olderImage(@Arg("services") List<String> services, @Arg("count") int count);

        @Name("Images not compared")
        MessageRef notCompared();

        @Name("The resource pack does not answer")
        MessageRef deadPack();

        @Name("Which pack")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef offeredPack(@Arg("url") String url);

        @Name("No finished backup")
        MessageRef noBackup();

        @Name("Only started backups")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef onlyStarted();

        @Name("No database dump")
        MessageRef noDump();

        @Name("What a missing dump means")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
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
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef permittedAge(@Arg("hours") long hours);

        @Name("A full disk")
        MessageRef disk(@Arg("percent") long percent);

        @Name("Used memory")
        MessageRef memory(@Arg("percent") long percent);

        @Name("The threshold")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef threshold(@Arg("percent") long percent);

        @Name("No memory limit")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef noLimit();

        @Name("A payment needs a look")
        MessageRef payment();

        @Name("An unknown reference")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef unknownReference(
                @Arg("payment") String payment, @Arg("amount") Money amount, @Arg("reference") String reference);

        @Name("A request no longer open")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef notOpen(
                @Arg("payment") String payment,
                @Arg("amount") Money amount,
                @Arg("reference") String reference,
                @Arg("status") PaymentRequestStatus status);

        @Name("A payment claimed twice")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef claimed(
                @Arg("payment") String payment, @Arg("amount") Money amount, @Arg("reference") String reference);

        @Name("A payment below every tier")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef noTier(
                @Arg("payment") String payment, @Arg("amount") Money amount, @Arg("reference") String reference);

        @Name("Several roles of one name")
        MessageRef roleAmbiguous(@Arg("name") String name);

        @Name("None of them taken")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef noneAdopted();

        @Name("A role not created")
        MessageRef roleNotCreated(@Arg("name") String name);

        @Name("The lock without its channel")
        MessageRef lockWithoutChannel();

        @Name("Nobody locked")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef nobodyLocked();

        @Name("Channels the lock does not close")
        MessageRef lockNotKept(@Arg("count") int count);

        @Name("Which channel, and what went wrong")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef failedIn(@Arg("channel") String channel, @Arg("error") String error);

        @Name("A role not changed")
        MessageRef roleNotChanged(@Arg("role") DiscordRole role, @Arg("given") boolean given);

        @Name("Who, and what went wrong")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef failedFor(@Arg("person") Mention person, @Arg("error") String error);

        @Name("A direct message not delivered")
        MessageRef dm();

        @Name("To whom")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef to(@Arg("person") Mention person);

        @Name("A link failed")
        MessageRef linkFailed();

        @Name("A purchase failed")
        MessageRef purchaseFailed();

        @Name("bunq refused a payment link")
        MessageRef linkRefused();

        @Name("Which request, and why bunq refused it")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef refused(@Arg("reference") String reference, @Arg("refusal") TabRefusal refusal);
    }
}
