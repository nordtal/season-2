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
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateKind;
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
        MessageRef person(@Arg("person") @Example("Alex") Mention person);

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
        MessageRef revokeAdmin(
                @Arg("below") @Example("Sam,Kim") List<String> below, @Arg("count") @Example("2") int count);

        @Name("Pack enforced")
        MessageRef enforcePack();

        @Name("Pack exempted")
        MessageRef exemptPack();

        @Name("Access granted")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef grantAccess(
                @Arg("days") @Example("30") int days, @Arg("until") @Example("2026-11-22T18:00:00Z") Instant until);

        @Name("Access revoked")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef revokeAccess(@Arg("grants") @Example("2") int grants);

        @Name("Play time set")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef setPlaytime(@Arg("seconds") @Example("PT12H5M") Duration seconds);

        @Name("Account linked")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef link();

        @Name("Account unlinked")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef unlink(@Arg("selfService") boolean selfService);

        @Name("Payment settled")
        MessageRef settle(
                @Arg("reference") @Example("NT-A1B2C3") String reference,
                @Arg("matched") PaymentMatch matched,
                @Arg("days") @Example("30") int days,
                @Arg("ordered") @Example("30") int ordered,
                @Arg("received") @Example("500") Money received,
                @Arg("donation") @Example("100") Money donation);

        @Name("Payment settled by hand")
        MessageRef settleByHand(
                @Arg("reference") @Example("NT-A1B2C3") String reference,
                @Arg("days") @Example("30") int days,
                @Arg("ordered") @Example("30") int ordered,
                @Arg("donation") @Example("100") Money donation);

        @Name("Phase changed")
        MessageRef setPhase(@Arg("from") SeasonPhase from, @Arg("to") SeasonPhase to);

        @Name("Phase changed, with a reason")
        MessageRef setPhaseBecause(
                @Arg("from") SeasonPhase from,
                @Arg("to") SeasonPhase to,
                @Arg("reason") @Example("The start event is over.") String reason);

        @Name("Opening set")
        MessageRef setLaunch(@Arg("to") @Example("2026-10-24T18:00:00Z") Instant to);

        @Name("Opening cleared")
        MessageRef clearLaunch();

        @Name("Paid time set")
        MessageRef setSmpStart(
                @Arg("to") @Example("2026-10-31T18:00:00Z") Instant to,
                @Arg("movedGrants") @Example("3") long movedGrants);

        @Name("Paid time cleared")
        MessageRef clearSmpStart();

        @Name("Security key added")
        MessageRef registerKey(@Arg("label") @Example("YubiKey") String label);

        @Name("Security key used")
        MessageRef heldKeyFor(
                @Arg("label") @Example("YubiKey") String label,
                @Arg("unlocked") boolean unlocked,
                @Arg("hold") @Example("PT15M") Duration hold);

        @Name("Security key renamed")
        MessageRef renameKey(@Arg("label") @Example("YubiKey") String label);

        @Name("Security key removed")
        MessageRef removeKey(@Arg("label") @Example("YubiKey") String label, @Arg("left") @Example("1") int left);

        @Name("Authenticator app added")
        MessageRef addAuthenticator();

        @Name("Authenticator app used")
        MessageRef usedAuthenticator(@Arg("hold") @Example("PT15M") Duration hold);

        @Name("Authenticator app removed")
        MessageRef removeAuthenticator(@Arg("keys") @Example("1") int keys);

        @Name("Security reset")
        MessageRef forgetEveryFactor(
                @Arg("keys") @Example("2") int keys,
                @Arg("authenticator") boolean authenticator,
                @Arg("sessions") @Example("3") int sessions);

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
        MessageRef console(
                @Arg("service") @Example("smp") String service,
                @Arg("command") @Example("say Restart in five minutes") String command);

        @Name("Settings saved")
        MessageRef saveSettings(
                @Arg("service") @Example("smp") String service, @Arg("group") @Example("chat") String group);

        @Name("Texts saved")
        MessageRef saveMessages(@Arg("bundle") @Example("steward") String bundle);

        @Name("Plugin added")
        MessageRef addPlugin(
                @Arg("service") @Example("smp") String service, @Arg("artifact") @Example("chunky") String artifact);

        @Name("Plugin removed")
        MessageRef removePlugin(
                @Arg("run") @Example("42") long run,
                @Arg("service") @Example("smp") String service,
                @Arg("artifact") @Example("chunky") String artifact);

        @Name("Run asked for")
        MessageRef requestRun(
                @Arg("run") @Example("42") long run,
                @Arg("kind") UpdateKind kind,
                @Arg("services") @Example("smp,proxy") List<String> services,
                @Arg("count") @Example("2") int count);

        @Name("Restore asked for")
        MessageRef requestRestore(
                @Arg("run") @Example("42") long run,
                @Arg("archive") @Example("nordtal-20261020-0400.dump") String archive);

        @Name("Run cancelled")
        MessageRef cancelRun(@Arg("run") @Example("42") long run, @Arg("kind") UpdateKind kind);

        @Name("Backup downloaded")
        MessageRef downloadBackup(
                @Arg("archive") @Example("nordtal-20261020-0400.dump") String archive,
                @Arg("size") @Example("1.2 GiB") MessageRef size);

        @Name("Backup downloaded, size unknown")
        MessageRef downloadBackupUnsized(@Arg("archive") @Example("nordtal-20261020-0400.dump") String archive);

        @Name("Announcement")
        MessageRef announce(@Arg("languages") @Example("English,Deutsch") List<String> languages);

        @Name("Objective completed")
        MessageRef completeObjective(@Arg("objective") @Example("Mine 64 diamonds") String objective);

        @Name("Aura set")
        MessageRef setAura(@Arg("aura") @Example("250") int aura);

        @Name("Spins set")
        MessageRef setSpins(@Arg("spins") @Example("3") int spins);

        @Name("Stack removed")
        MessageRef removeStack(
                @Arg("item") @Example("minecraft:oak_log") String item, @Arg("amount") @Example("16") int amount);

        @Name("Milestone unlocked")
        MessageRef unlockMilestone(@Arg("milestone") @Example("The Nether") String milestone);

        @Name("Hunger Games started")
        MessageRef startGame();

        @Name("Access anchored to the grant")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef unanchoredGrant(@Arg("from") @Example("2026-10-24T18:00:00Z") Instant from);

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
        MessageRef title(@Arg("kind") UpdateKind kind, @Arg("stage") RunStage stage);

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

        @Name("What happens to a container's image")
        @Shown(Display.STEWARD)
        MessageRef image(@Arg("image") RunStep.Image image);

        @Name("An artefact no version names")
        @Shown(Display.STEWARD)
        MessageRef artefact(@Arg("artefact") @Example("paper") String artefact);

        @Name("Players moved off a service and back")
        @Shown(Display.STEWARD)
        MessageRef players(
                @Arg("moved") @Example("12") long moved,
                @Arg("from") @Example("smp") String from,
                @Arg("to") @Example("limbo") String to,
                @Arg("returned") @Example("12") long returned);

        @Name("Images a cleanup removed")
        @Shown(Display.STEWARD)
        MessageRef imagesRemoved(
                @Arg("images") @Example("4") long images, @Arg("freed") @Example("850 MiB") MessageRef freed);

        @Name("The build cache a cleanup freed")
        @Shown(Display.STEWARD)
        MessageRef cacheFreed(@Arg("freed") @Example("2.3 GiB") MessageRef freed);

        @Name("The room a backup needs and finds")
        @Shown(Display.STEWARD)
        MessageRef room(
                @Arg("needed") @Example("6.5 GiB") MessageRef needed, @Arg("free") @Example("14 GiB") MessageRef free);

        @Name("An offsite copy")
        @Shown(Display.STEWARD)
        MessageRef offsite(
                @Arg("snapshot") @Example("4f1c2d") String snapshot,
                @Arg("added") @Example("120 MiB") MessageRef added);

        @Name("An archive a step saved")
        @Shown(Display.STEWARD)
        MessageRef archiveSaved(
                @Arg("file") @Example("nordtal-20261020-0400.dump") String file,
                @Arg("size") @Example("1.2 GiB") MessageRef size,
                @Arg("took") @Example("PT2M") Duration took,
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
        MessageRef words(@Arg("text") @Example("The End opens tonight at eight.") String text);

        @Name("All clear")
        MessageRef clear(@Arg("type") AlertType type);

        @Name("Several of one type at once")
        MessageRef several(@Arg("subjects") @Example("database dump,disk") List<String> subjects);

        @Name("Which run")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef run(@Arg("run") @Example("42") long run);

        @Name("A step of a failed run that failed or warned")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef step(
                @Arg("kind") @Example("Room check") MessageRef kind,
                @Arg("why") @Example("admin/run.no-build") MessageRef why);

        @Name("A step of a failed run on one service that failed or warned")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef stepOn(
                @Arg("kind") @Example("Room check") MessageRef kind,
                @Arg("service") @Example("smp") String service,
                @Arg("why") @Example("admin/run.no-build") MessageRef why);

        @Name("No services")
        MessageRef noServices();

        @Name("A service is not running")
        MessageRef notRunning(@Arg("service") @Example("smp") String service);

        @Name("A service is unhealthy")
        MessageRef unhealthy(@Arg("service") @Example("smp") String service);

        @Name("Failing healthcheck")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef healthFails(@Arg("service") @Example("smp") String service);

        @Name("Docker's word")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef dockerState(
                @Arg("service") @Example("smp") String service, @Arg("state") @Example("restarting") String state);

        @Name("A service restarts")
        MessageRef restarting(@Arg("service") @Example("smp") String service);

        @Name("Restarted by Docker")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef restartedByDocker(
                @Arg("service") @Example("smp") String service, @Arg("count") @Example("3") long count);

        @Name("A one-shot service failed")
        MessageRef failedOnce(@Arg("service") @Example("smp") String service);

        @Name("The exit code of a one-shot service")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef exitCode(@Arg("service") @Example("smp") String service, @Arg("code") @Example("137") long code);

        @Name("Older images")
        MessageRef olderImage(
                @Arg("services") @Example("smp,proxy") List<String> services, @Arg("count") @Example("2") int count);

        @Name("Images not compared")
        MessageRef notCompared();

        @Name("The resource pack does not answer")
        MessageRef deadPack();

        @Name("Which pack")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef offeredPack(@Arg("url") @Example("https://pack.nordtal.eu/nordtal.zip") String url);

        @Name("No finished backup")
        MessageRef noBackup();

        @Name("No database dump")
        MessageRef noDump();

        @Name("No archive of a volume")
        MessageRef noArchive(@Arg("volume") @Example("smp-world") String volume);

        @Name("An old dump")
        MessageRef oldDump();

        @Name("An old archive")
        MessageRef oldArchive(@Arg("volume") @Example("smp-world") String volume);

        @Name("No copy off the host")
        MessageRef noOffsite();

        @Name("An old copy off the host")
        MessageRef oldOffsite();

        @Name("A backup series without a file")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef missing(@Arg("subject") @Example("database dump") String subject);

        @Name("The age of a backup series against the permitted age")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef age(
                @Arg("subject") @Example("database dump") String subject,
                @Arg("hours") @Example("30") long hours,
                @Arg("limit") @Example("26") long limit);

        @Name("A full disk")
        MessageRef disk();

        @Name("How full the disk is")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef diskUse(
                @Arg("percent") @Example("91") long percent, @Arg("threshold") @Example("90") long threshold);

        @Name("Used memory")
        MessageRef memory();

        @Name("How much memory is used")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef memoryUse(
                @Arg("percent") @Example("93") long percent, @Arg("threshold") @Example("90") long threshold);

        @Name("A payment needs a look")
        MessageRef payment();

        @Name("An unknown reference")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef unknownReference(
                @Arg("payment") @Example("1093") String payment,
                @Arg("amount") @Example("500") Money amount,
                @Arg("reference") @Example("NT-A1B2C3") String reference);

        @Name("A request no longer open")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef notOpen(
                @Arg("payment") @Example("1093") String payment,
                @Arg("amount") @Example("500") Money amount,
                @Arg("reference") @Example("NT-A1B2C3") String reference,
                @Arg("status") PaymentRequestStatus status);

        @Name("A payment claimed twice")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef claimed(
                @Arg("payment") @Example("1093") String payment,
                @Arg("amount") @Example("500") Money amount,
                @Arg("reference") @Example("NT-A1B2C3") String reference);

        @Name("A payment below every tier")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef noTier(
                @Arg("payment") @Example("1093") String payment,
                @Arg("amount") @Example("150") Money amount,
                @Arg("reference") @Example("NT-A1B2C3") String reference);

        @Name("Several roles of one name")
        MessageRef roleAmbiguous(@Arg("name") @Example("Player") String name);

        @Name("None of them taken")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef noneAdopted();

        @Name("A role not created")
        MessageRef roleNotCreated(@Arg("name") @Example("Player") String name);

        @Name("The lock without its channel")
        MessageRef lockWithoutChannel();

        @Name("Nobody locked")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef nobodyLocked();

        @Name("Channels the lock does not close")
        MessageRef lockNotKept();

        @Name("Which channel, and what went wrong")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef failedIn(
                @Arg("channel") @Example("#announcements") String channel,
                @Arg("error") @Example("Missing Permissions") String error);

        @Name("A role not changed")
        MessageRef roleNotChanged(@Arg("role") DiscordRole role, @Arg("given") boolean given);

        @Name("Who, and what went wrong")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef failedFor(
                @Arg("person") @Example("Alex") Mention person,
                @Arg("error") @Example("Cannot send messages to this user") String error);

        @Name("A direct message not delivered")
        MessageRef dm();

        @Name("To whom")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef to(@Arg("person") @Example("Alex") Mention person);

        @Name("A link failed")
        MessageRef linkFailed();

        @Name("A purchase failed")
        MessageRef purchaseFailed();

        @Name("bunq refused a payment link")
        MessageRef linkRefused();

        @Name("Which request, and why bunq refused it")
        @Shown({Display.STEWARD, Display.DISCORD_TEXT})
        MessageRef refused(
                @Arg("reference") @Example("NT-A1B2C3") String reference, @Arg("refusal") TabRefusal refusal);
    }
}
