package eu.nordtal.season.steward.texts;

import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.MessageSpec;
import eu.nordtal.season.messages.spec.MessageSpecs;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.spec.Shown;
import eu.nordtal.season.messages.spec.TextFormat;
import eu.nordtal.season.messages.value.Example;
import java.time.Duration;
import java.time.Instant;

/** The English texts of Steward, which the browser renders and an admin may override. */
@MessageSpec(value = "steward", format = TextFormat.PLAIN)
@Shown(Display.STEWARD)
public interface StewardTexts {

    /** The shared instance. */
    StewardTexts TEXTS = MessageSpecs.create(StewardTexts.class);

    Steward steward();

    @Name("Steward")
    interface Steward {

        Service service();

        Artifact artifact();

        Image image();

        Answer answer();

        Said said();

        JournalPage journal();

        OperationsPage operations();

        UpdatesPage updates();

        BackupsPage backups();

        Forms form();

        GameTexts game();

        SettingsEditor settings();

        MessageEditorTexts messageEditor();

        DocumentEditorTexts documentEditor();

        AnnouncementsPage announcements();

        Failures failure();

        NetworkPanel network();

        IdentityCard identity();

        Shell shell();

        NotificationsPanel notifications();

        OverviewPage overview();

        SeasonPage season();

        TranslationsPage texts();

        ServicePage servicePage();

        KeysPage keys();

        PeoplePage people();

        PaymentsPage payments();

        BackupDialogs backupSettings();

        @Name("Service")
        interface Service {

            @Name("Service state")
            MessageRef state(@Arg("state") ServiceWord state);

            @Name("Docker's word")
            MessageRef dockerState(@Arg("state") @Example("restarting") String state);

            @Name("Held down")
            MessageRef heldSince(
                    @Arg("since") @Example("2026-10-23T18:40:00Z") Instant since,
                    @Arg("state") @Example("exited") String state);

            @Name("How a one-shot ended")
            MessageRef exited(
                    @Arg("code") @Example("137") int code, @Arg("at") @Example("2026-10-23T18:40:00Z") Instant at);

            @Name("Failing healthcheck")
            MessageRef unhealthy();

            @Name("No verdict yet")
            MessageRef starting();

            @Name("Healthcheck")
            MessageRef health(@Arg("health") @Example("unhealthy") String health);

            @Name("No healthcheck")
            MessageRef noHealth();

            @Name("Not read yet")
            MessageRef notRead();
        }

        @Name("Artifact")
        interface Artifact {

            @Name("Artifact status")
            MessageRef status(@Arg("status") ArtifactStatus status);
        }

        @Name("Image")
        interface Image {

            @Name("Image drift")
            MessageRef drift(@Arg("drift") ImageResult.State drift);

            @Name("Unchecked image explained")
            MessageRef uncheckedTip();

            @Name("Image")
            MessageRef label();

            @Name("Jars built here")
            MessageRef localJars();
        }

        /** The reasons for which Steward refuses a request. */
        @Name("Answer")
        interface Answer {

            @Name("No longer an admin")
            MessageRef notAdmin();

            @Name("Already an admin")
            MessageRef alreadyAdmin();

            @Name("Not a member of the guild")
            MessageRef notAMember();

            @Name("Too many grants this hour")
            MessageRef grantsPerHour(@Arg("count") @Example("10") int count);

            @Name("Revoking oneself")
            MessageRef self();

            @Name("Not granted below")
            MessageRef notBelow();

            @Name("Exempt already")
            MessageRef exemptAlready();

            @Name("Enforced already")
            MessageRef enforcedAlready();

            @Name("Nobody by that id")
            MessageRef unknownPerson();

            @Name("No sources")
            MessageRef noSources();

            @Name("No such request")
            MessageRef noRequest(@Arg("request") @Example("42") String request);

            @Name("No such announcement")
            MessageRef noAnnouncement(@Arg("announcement") @Example("7") long announcement);

            @Name("No such bundle")
            MessageRef noBundle(@Arg("bundle") @Example("smp-chat") String bundle);

            @Name("Not the JSON taken")
            MessageRef notJson();

            @Name("Empty body")
            MessageRef empty();

            @Name("Empty announcement text")
            MessageRef emptyText(@Arg("language") @Example("Deutsch") String language);

            @Name("Countdown over")
            MessageRef tooLate();

            @Name("Key not held")
            MessageRef keyNotHeld();

            @Name("Key not held lately")
            MessageRef keyNotRecent();

            @Name("No key")
            MessageRef noKey();

            @Name("Ceremony from elsewhere")
            MessageRef ceremonyElsewhere(@Arg("registration") boolean registration);

            @Name("No such key")
            MessageRef noSuchKey();

            @Name("Last factor")
            MessageRef lastFactor();

            @Name("No authenticator")
            MessageRef noAuthenticator();

            @Name("Authenticator there")
            MessageRef authenticatorThere();

            @Name("Authenticator not offered")
            MessageRef authenticatorNotOffered();

            @Name("Code wrong")
            MessageRef codeWrong();

            @Name("Code taken")
            MessageRef codeTaken();

            @Name("Too many codes")
            MessageRef tooManyCodes(@Arg("until") @Example("2026-10-23T18:55:00Z") Instant until);

            @Name("Sign-in not configured")
            MessageRef signInUnconfigured(@Arg("missing") @Example("STEWARD_DISCORD_CLIENT_ID") String missing);

            @Name("Sign-in from elsewhere")
            MessageRef signInElsewhere();

            @Name("No code")
            MessageRef noCode();

            @Name("Not in the guild")
            MessageRef notInGuild();

            @Name("In the guild, not an admin")
            MessageRef notAnAdmin(@Arg("name") @Example("Alex") String name);

            @Name("Discord refused the sign-in")
            MessageRef signInRefused(
                    @Arg("status") @Example("400") int status,
                    @Arg("uri") @Example("https://steward.nordtal.eu/auth/discord") String uri);

            @Name("No access token")
            MessageRef noAccessToken();

            @Name("Discord answered")
            MessageRef discordAnswered(@Arg("status") @Example("429") int status);

            @Name("Discord unreachable")
            MessageRef discordUnreachable(@Arg("error") @Example("Connection refused") String error);

            @Name("Interrupted")
            MessageRef interrupted();

            @Name("Agent not configured")
            MessageRef agentUnconfigured();

            @Name("No such service")
            MessageRef noService(@Arg("service") @Example("smp") String service);

            @Name("No such settings group")
            MessageRef noSettings(
                    @Arg("service") @Example("smp") String service, @Arg("group") @Example("chat") String group);

            @Name("No such setting")
            MessageRef noSetting(
                    @Arg("group") @Example("chat") String group, @Arg("path") @Example("format.join") String path);

            @Name("Settings changed meanwhile")
            MessageRef settingsChanged(
                    @Arg("service") @Example("smp") String service, @Arg("group") @Example("chat") String group);

            @Name("Settings refused by the server")
            MessageRef settingsRefused(
                    @Arg("refusal") @Example("view-distance must be between 2 and 32") String refusal);

            @Name("A secret setting")
            MessageRef secretSetting(@Arg("path") @Example("bank.key") String path);

            @Name("A setting of another type")
            MessageRef notOfType(
                    @Arg("path") @Example("view-distance") String path,
                    @Arg("expected") Expected expected,
                    @Arg("text") @Example("far") String text);

            @Name("A section that has to stay")
            MessageRef keepSection(
                    @Arg("path") @Example("rewards") String path,
                    @Arg("field") @Example("name") String field,
                    @Arg("value") @Example("Diamond") String value);

            @Name("No such backup")
            MessageRef noBackup(@Arg("backup") @Example("17") String backup);

            @Name("Backup not finished")
            MessageRef backupUnfinished(@Arg("backup") @Example("nordtal-20261020-0400.dump") String backup);

            @Name("Restore not confirmed")
            MessageRef confirmRestore(@Arg("replaces") @Example("smp-world") String replaces);

            @Name("No such message")
            MessageRef noMessage(
                    @Arg("bundle") @Example("steward") String bundle,
                    @Arg("key") @Example("steward.answer.no-bundle") String key);

            @Name("Override refused")
            MessageRef overrideRefused(
                    @Arg("key") @Example("steward.answer.no-bundle") String key,
                    @Arg("language") @Example("Deutsch") String language);

            @Name("Preview refused")
            MessageRef previewRefused(
                    @Arg("key") @Example("steward.answer.no-bundle") String key,
                    @Arg("language") @Example("Deutsch") String language);

            @Name("Nowhere to preview")
            MessageRef noPreview(@Arg("key") @Example("steward.answer.no-bundle") String key);

            @Name("Draft not drawn")
            MessageRef draftUndrawable(
                    @Arg("document") @Example("rules") String document,
                    @Arg("reason") @Example("an embed holds at most 25 fields") String reason);

            @Name("No such document")
            MessageRef noDocument(
                    @Arg("namespace") @Example("bot") String namespace, @Arg("name") @Example("rules") String name);

            @Name("Document refused")
            MessageRef documentRefused(
                    @Arg("document") @Example("rules") String document,
                    @Arg("problem") @Example("the title is empty") String problem);

            @Name("Shared part name refused")
            MessageRef sharedPartName(@Arg("name") @Example("Footer") String name);

            @Name("Draft not sent")
            MessageRef draftRefused(
                    @Arg("document") @Example("rules") String document,
                    @Arg("problem") @Example("the title is empty") String problem);

            @Name("Draft not a message")
            MessageRef draftNotMessage(@Arg("document") @Example("rules") String document);

            @Name("Image refused")
            MessageRef imageRefused(@Arg("mebibytes") @Example("8") int mebibytes);

            @Name("No player to preview to")
            MessageRef previewUnlinked();

            @Name("Player not in a game")
            MessageRef previewOffline();

            @Name("Grant too long")
            MessageRef grantDays(@Arg("most") @Example("365") int most);

            @Name("Play time out of range")
            MessageRef playtimeRange(@Arg("most") @Example("PT2400H") Duration most);

            @Name("Aura out of range")
            MessageRef auraRange(@Arg("most") @Example("10000") int most);

            @Name("Spins out of range")
            MessageRef spinsRange(@Arg("most") @Example("100") int most);

            @Name("Player not linked")
            MessageRef inventoryUnlinked();

            @Name("Inventory held elsewhere")
            MessageRef inventoryHeld(@Arg("server") @Example("smp") String server);

            @Name("Server did not answer")
            MessageRef serverSilent(@Arg("server") @Example("smp") String server);

            @Name("Announcement too long")
            MessageRef tooLong(
                    @Arg("language") @Example("Deutsch") String language, @Arg("most") @Example("2000") int most);

            @Name("Web push not configured")
            MessageRef pushUnconfigured();

            @Name("No such push subscription")
            MessageRef noSubscription();

            @Name("Push subscription gone")
            MessageRef subscriptionGone();

            @Name("Push not accepted")
            MessageRef pushNotAccepted();

            @Name("Key name length")
            MessageRef keyName(@Arg("most") @Example("64") int most);

            @Name("Ceremony from an older Steward")
            MessageRef ceremonyStale(@Arg("registration") boolean registration);

            @Name("Browser answer unreadable")
            MessageRef answerUnreadable();

            @Name("Ceremony for another account")
            MessageRef ceremonyOtherAccount(@Arg("registration") boolean registration);

            @Name("Key refused")
            MessageRef keyRefused(
                    @Arg("registration") boolean registration,
                    @Arg("why") @Example("the key is already registered") String why);

            @Name("Key not accepted")
            MessageRef keyNotAccepted();
        }

        /** The messages that Steward shows after an action. */
        @Name("Said")
        interface Said {

            @Name("A setting saved")
            MessageRef setting(
                    @Arg("network") boolean network,
                    @Arg("service") @Example("smp") String service,
                    @Arg("live") boolean live);

            @Name("A setting saved unchecked")
            MessageRef settingUnchecked(@Arg("service") @Example("smp") String service, @Arg("live") boolean live);

            @Name("A message saved")
            MessageRef message();

            @Name("An announcement's line")
            MessageRef announced(@Arg("posted") boolean posted, @Arg("language") @Example("Deutsch") String language);

            @Name("A preview sent")
            MessageRef previewSent();

            @Name("No guild id")
            MessageRef noGuildId();

            @Name("The channels not published")
            MessageRef channelsNotPublished();

            @Name("The guild not listed")
            MessageRef guildNotListed();

            @Name("A service's own words")
            MessageRef words(@Arg("text") @Example("The End opens tonight at eight.") String text);
        }
    }

    /** The type of a setting value. A game's key is a text the settings form checks by its shape. */
    enum Expected {
        INTEGER,
        DECIMAL,
        BOOLEAN,
        KEY
    }

    /** The words of a service's badge and dot: Docker states and health states. */
    enum ServiceWord {
        RUNNING,
        HEALTHY,
        STARTING,
        UNHEALTHY,
        HELD,
        STANDBY,
        COMPLETED,
        FAILED,
        CREATED,
        RESTARTING,
        REMOVING,
        PAUSED,
        EXITED,
        DEAD
    }

    /** The status of an artifact in the plan of the agent. */
    enum ArtifactStatus {
        UP_TO_DATE,
        OUTDATED,
        MISSING,
        UNSUPPORTED,
        MOUNT_MISSING,
        UNRESOLVED,
        NOT_IN_RELEASE
    }
}
