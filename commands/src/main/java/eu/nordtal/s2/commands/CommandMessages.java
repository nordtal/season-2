package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.message.spec.Arg;
import eu.nordtal.s2.common.message.spec.Key;
import eu.nordtal.s2.common.message.spec.MessageSpec;
import eu.nordtal.s2.common.message.spec.MessageSpecs;
import eu.nordtal.s2.common.message.spec.Name;

/**
 * Every message of the commands bundle, one method per key.
 */
@MessageSpec("commands")
public interface CommandMessages {

    /** The messages; stateless, so one instance serves every caller. */
    CommandMessages MESSAGES = MessageSpecs.create(CommandMessages.class);

    Command command();

    @Name("Command")
    interface Command {

        @Name("Not admin")
        MessageRef notAdmin();

        @Name("Cancelled")
        MessageRef cancelled();

        @Name("Unknown")
        MessageRef unknown();

        @Name("Player offline")
        MessageRef playerOffline();

        @Name("Not a choice")
        MessageRef notAChoice(@Arg("typed") Object typed, @Arg("argument") Object argument, @Arg("choices") Object choices);

        @Name("Not from console")
        MessageRef notFromConsole();

        @Name("Account unreachable")
        MessageRef accountUnreachable();

        @Name("Player unlinked")
        MessageRef playerUnlinked(@Arg("player") Object player);

        Confirm confirm();

        @Name("Confirm")
        interface Confirm {

            @Name("Retype")
            MessageRef retype(@Arg("command") Object command, @Arg("seconds") Object seconds);

            @Name("Expired")
            MessageRef expired(@Arg("command") Object command);

            @Name("Yes")
            MessageRef yes();

            @Name("No")
            MessageRef no();

            @Name("Discord")
            MessageRef discord(@Arg("command") Object command);

            @Name("Stale")
            MessageRef stale();
        }

        Help help();

        @Name("Help")
        interface Help {

            @Name("Header")
            MessageRef header(@Arg("command") Object command);

            @Name("Line")
            MessageRef line(@Arg("usage") Object usage, @Arg("what") Object what);

            @Name("Usage")
            MessageRef usage(@Arg("usage") Object usage);

            @Name("What")
            MessageRef what(@Arg("what") Object what);

            @Name("Nothing")
            MessageRef nothing();
        }

        DescribeMessages describe();

        @Name("Command help")
        interface DescribeMessages {

            @Name("Aura")
            MessageRef aura();

            @Name("Announce")
            MessageRef announce();

            Phase phase();

            @Name("Phase")
            interface Phase {

                @Name("Show")
                MessageRef show();

                @Name("Set")
                MessageRef set();

                @Name("Launch")
                MessageRef launch();

                @Name("SMP start")
                MessageRef smpStart();
            }

            Smp smp();

            @Name("SMP")
            interface Smp {

                @Name("Reload")
                MessageRef reload();

                @Name("Aura")
                MessageRef aura();

                @Name("Access")
                MessageRef access();

                @Name("Status")
                MessageRef status();

                Objective objective();

                @Name("Objective")
                interface Objective {

                    @Name("Complete")
                    MessageRef complete();
                }

                Milestone milestone();

                @Name("Milestone")
                interface Milestone {

                    @Name("Unlock")
                    MessageRef unlock();
                }
            }

            Hg hg();

            @Name("Hunger Games")
            interface Hg {

                @Name("Start")
                MessageRef start();

                @Name("Ready status")
                MessageRef readyStatus();

                @Name("Reload")
                MessageRef reload();
            }

            @Name("Limbo")
            Reload limbo();

            interface Reload {

                @Name("Reload")
                MessageRef reload();
            }

            @Name("Network")
            Reload network();

            Root root();

            @Name("Root")
            interface Root {

                @Name("SMP")
                MessageRef smp();

                @Name("Hunger Games")
                MessageRef hg();

                @Name("Limbo")
                MessageRef limbo();

                @Name("Network")
                MessageRef network();

                @Name("Phase")
                MessageRef phase();

                @Name("Access")
                MessageRef access();

                @Name("Messages")
                MessageRef messages();

                @Name("Update")
                MessageRef update();

                @Name("Backup")
                MessageRef backup();
            }

            Access access();

            @Name("Access")
            interface Access {

                @Name("Status")
                MessageRef status();

                @Name("Grant")
                MessageRef grant();

                @Name("Revoke")
                MessageRef revoke();

                @Name("Unlink")
                MessageRef unlink();

                @Name("Settle")
                MessageRef settle();

                @Name("Reload")
                MessageRef reload();
            }

            Backup backup();

            @Name("Backup")
            interface Backup {

                @Name("Now")
                MessageRef now();
            }

            Update update();

            @Name("Update")
            interface Update {

                @Name("Check")
                MessageRef check();

                @Name("Now")
                MessageRef now();

                @Name("Restart")
                MessageRef restart();

                @Name("Cancel")
                MessageRef cancel();

                @Name("Down")
                MessageRef down();

                @Name("Start")
                MessageRef start();
            }
        }

        Remote remote();

        @Name("Remote")
        interface Remote {

            @Name("Sent")
            MessageRef sent(@Arg("target") Object target);

            @Name("No answer")
            MessageRef noAnswer(@Arg("target") Object target);

            @Name("Still running")
            MessageRef stillRunning();

            @Name("Failed")
            MessageRef failed();

            @Name("Unknown")
            MessageRef unknown(@Arg("command") Object command);

            @Name("Arguments")
            MessageRef arguments(@Arg("command") Object command);

            @Name("Silent")
            MessageRef silent();
        }

        Target target();

        @Name("Target")
        interface Target {

            @Name("SMP")
            @Key("SMP")
            MessageRef smp();

            @Name("Hunger Games")
            @Key("HUNGER_GAMES")
            MessageRef hungerGames();

            @Name("Limbo")
            @Key("LIMBO")
            MessageRef limbo();

            @Name("Proxy")
            @Key("PROXY")
            MessageRef proxy();

            @Name("Bot")
            @Key("BOT")
            MessageRef bot();

            @Name("Local")
            @Key("LOCAL")
            MessageRef local();
        }

        Argument argument();

        @Name("Argument")
        interface Argument {

            @Name("Player")
            MessageRef player();

            @Name("Delta")
            MessageRef delta();

            @Name("Member")
            MessageRef member();

            @Name("Confirm")
            MessageRef confirm();

            @Name("Key")
            MessageRef key();

            @Name("When")
            MessageRef when();

            @Name("Phase")
            MessageRef phase();

            @Name("Days")
            MessageRef days();

            @Name("Reference")
            MessageRef reference();
        }
    }

    Smp smp();

    @Name("SMP")
    interface Smp {

        Aura aura();

        @Name("Aura")
        interface Aura {

            @Name("Own")
            MessageRef own(@Arg("aura") Object aura, @Arg("rank") Object rank, @Arg("total") Object total);

            @Name("Top")
            MessageRef top(@Arg("count") Object count);

            @Name("Line")
            MessageRef line(@Arg("place") Object place, @Arg("player") Object player, @Arg("aura") Object aura);

            @Name("Empty")
            MessageRef empty();

            @Name("Unlinked")
            MessageRef unlinked();

            @Name("Nobody")
            MessageRef nobody();

            @Name("Failed")
            MessageRef failed();
        }

        Access access();

        @Name("Access")
        interface Access {

            @Name("Active")
            MessageRef active(@Arg("until") Object until);

            @Name("Expired")
            MessageRef expired(@Arg("since") Object since);

            @Name("Failed")
            MessageRef failed();

            @Name("Linked")
            MessageRef linked(@Arg("player") Object player, @Arg("discord") Object discord);

            @Name("Never")
            MessageRef never();

            @Name("No payment")
            MessageRef noPayment();

            @Name("Payment")
            MessageRef payment(@Arg("reference") Object reference, @Arg("days") Object days, @Arg("amount") Object amount, @Arg("since") Object since);

            @Name("Payment unknown")
            MessageRef paymentUnknown();

            @Name("Payment unstarted")
            MessageRef paymentUnstarted(@Arg("reference") Object reference, @Arg("days") Object days, @Arg("since") Object since);

            @Name("Unlinked")
            MessageRef unlinked(@Arg("player") Object player);
        }

        Admin admin();

        @Name("Admin")
        interface Admin {

            @Name("Aura changed")
            MessageRef auraChanged(@Arg("player") Object player, @Arg("delta") Object delta);

            @Name("Aura unknown")
            MessageRef auraUnknown(@Arg("player") Object player, @Arg("delta") Object delta);

            @Name("Milestone unlocked")
            MessageRef milestoneUnlocked(@Arg("key") Object key);

            @Name("No active milestone")
            MessageRef noActiveMilestone();

            @Name("No such objective")
            MessageRef noSuchObjective();

            @Name("Player offline")
            MessageRef playerOffline();

            @Name("Reloaded")
            MessageRef reloaded();

            @Name("Track refused")
            MessageRef trackRefused(@Arg("problems") Object problems);

            @Name("Target unlinked")
            MessageRef targetUnlinked(@Arg("player") Object player);

            @Name("Objective completed")
            MessageRef objectiveCompleted(@Arg("key") Object key, @Arg("milestone") Object milestone);

            @Name("Reload failed")
            MessageRef reloadFailed();

            @Name("Read failed")
            MessageRef readFailed();
        }

        Status status();

        @Name("Status")
        interface Status {

            @Name("Milestone")
            MessageRef milestone(@Arg("milestone") Object milestone, @Arg("percent") Object percent);

            @Name("Finished")
            MessageRef finished();

            @Name("Online")
            MessageRef online(@Arg("online") Object online);

            @Name("Failed")
            MessageRef failed();

            @Key("online")
            Online onlineSection();

            @Name("Online")
            interface Online {

                @Name("None")
                MessageRef none();

                @Name("One")
                MessageRef one();
            }
        }
    }

    Phase phase();

    @Name("Phase")
    interface Phase {

        @Name("Current")
        MessageRef current(@Arg("phase") Object phase);

        @Name("Dates")
        MessageRef dates(@Arg("launch") Object launch, @Arg("smpStart") Object smpStart, @Arg("zone") Object zone);

        @Name("Unknown")
        MessageRef unknown(@Arg("value") Object value, @Arg("phases") Object phases);

        @Name("Changed")
        MessageRef changed(@Arg("previous") Object previous, @Arg("current") Object current);

        @Name("Unchanged")
        MessageRef unchanged(@Arg("phase") Object phase);

        @Name("Failed")
        MessageRef failed();

        @Name("Confirm")
        MessageRef confirm(@Arg("previous") Object previous, @Arg("current") Object current, @Arg("consequence") Object consequence);

        @Key("current")
        Current currentSection();

        @Name("Current")
        interface Current {

            @Name("Unread")
            MessageRef unread(@Arg("phase") Object phase);
        }

        Date date();

        @Name("Date")
        interface Date {

            @Name("Unset")
            MessageRef unset();

            @Name("Confirm")
            MessageRef confirm(@Arg("what") Object what, @Arg("when") Object when);

            @Name("Invalid")
            MessageRef invalid(@Arg("pattern") Object pattern, @Arg("zone") Object zone, @Arg("clear") Object clear);

            @Name("Set")
            MessageRef set(@Arg("what") Object what, @Arg("current") Object current, @Arg("previous") Object previous);

            @Name("Unchanged")
            MessageRef unchanged(@Arg("what") Object what, @Arg("current") Object current);

            @Name("Cleared")
            MessageRef cleared(@Arg("what") Object what);

            @Name("Moved")
            MessageRef moved(@Arg("grants") Object grants, @Arg("accounts") Object accounts);

            @Name("None moved")
            MessageRef noneMoved();

            @Name("Kept")
            MessageRef kept();

            @Name("Refused")
            MessageRef refused(@Arg("reason") Object reason);

            @Name("Failed")
            MessageRef failed();

            @Key("moved")
            Moved movedSection();

            @Name("Moved")
            interface Moved {

                @Name("One")
                MessageRef one();

                @Name("One account")
                MessageRef oneAccount(@Arg("grants") Object grants);
            }

            What what();

            @Name("What")
            interface What {

                @Name("Launch")
                MessageRef launch();

                @Name("SMP start")
                MessageRef smpStart();
            }
        }

        Read read();

        @Name("Read")
        interface Read {

            @Name("Failed")
            MessageRef failed();

            @Key("failed")
            Failed failedSection();

            @Name("Failed")
            interface Failed {

                @Name("Only")
                MessageRef only();
            }
        }

        @Key("confirm")
        Confirm confirmSection();

        @Name("Confirm")
        interface Confirm {

            @Name("Same")
            MessageRef same(@Arg("current") Object current, @Arg("consequence") Object consequence);
        }

        Consequence consequence();

        @Name("Consequence")
        interface Consequence {

            @Name("Pre launch")
            @Key("PRE_LAUNCH")
            MessageRef preLaunch();

            @Name("Pre event")
            @Key("PRE_EVENT")
            MessageRef preEvent();

            @Name("Start event")
            @Key("START_EVENT")
            MessageRef startEvent();

            @Name("SMP")
            @Key("SMP")
            MessageRef smp();

            @Name("Maintenance")
            @Key("MAINTENANCE")
            MessageRef maintenance();
        }
    }

    Hg hg();

    @Name("Hunger Games")
    interface Hg {

        Admin admin();

        @Name("Admin")
        interface Admin {

            @Name("Reload failed")
            MessageRef reloadFailed();

            @Name("Reloaded")
            MessageRef reloaded();
        }

        ReadyStatus readyStatus();

        @Name("Ready status")
        interface ReadyStatus {

            @Name("Header")
            MessageRef header();

            @Name("Line")
            MessageRef line(@Arg("team") Object team, @Arg("status") Object status);

            @Name("Not ready")
            MessageRef notReady();

            @Name("Ready")
            MessageRef ready();
        }

        Start start();

        @Name("Start")
        interface Start {

            @Name("Below hard minimum")
            MessageRef belowHardMinimum(@Arg("minimum") Object minimum, @Arg("count") Object count);

            @Name("Below soft minimum")
            MessageRef belowSoftMinimum(@Arg("count") Object count, @Arg("minimum") Object minimum, @Arg("seconds") Object seconds);

            @Name("Confirm expired")
            MessageRef confirmExpired();

            @Name("No game")
            MessageRef noGame();

            @Name("Started")
            MessageRef started(@Arg("count") Object count);

            @Name("Wrong state")
            MessageRef wrongState(@Arg("state") Object state);

            @Name("Read failed")
            MessageRef readFailed();

            @Name("Failed")
            MessageRef failed();
        }
    }

    Limbo limbo();

    @Name("Limbo")
    interface Limbo {

        Admin admin();

        @Name("Admin")
        interface Admin {

            @Name("Reloaded")
            MessageRef reloaded();

            @Name("Reload failed")
            MessageRef reloadFailed();
        }
    }

    Network network();

    @Name("Network")
    interface Network {

        @Name("Reloaded")
        MessageRef reloaded();

        @Name("Reload failed")
        MessageRef reloadFailed();
    }

    Access access();

    @Name("Access")
    interface Access {

        @Name("Header")
        MessageRef header(@Arg("player") Object player, @Arg("discord") Object discord);

        @Name("Until")
        MessageRef until(@Arg("until") Object until);

        @Name("None")
        MessageRef none();

        @Name("Donor")
        MessageRef donor(@Arg("donor") Object donor);

        @Name("Yes")
        MessageRef yes();

        @Name("No")
        MessageRef no();

        @Name("Language")
        MessageRef language(@Arg("language") Object language);

        @Name("Linked")
        MessageRef linked(@Arg("account") Object account);

        @Name("None linked")
        MessageRef noneLinked();

        @Name("Not linked")
        MessageRef notLinked();

        @Name("No such member")
        MessageRef noSuchMember(@Arg("discord") Object discord);

        @Name("Failed")
        MessageRef failed();

        @Name("Granted")
        MessageRef granted(@Arg("days") Object days, @Arg("until") Object until);

        @Name("Revoked")
        MessageRef revoked(@Arg("count") Object count);

        @Name("Unlinked")
        MessageRef unlinked(@Arg("member") Object member);

        Grants grants();

        @Name("Grants")
        interface Grants {

            @Name("Header")
            MessageRef header();

            @Name("None")
            MessageRef none();

            @Name("Line")
            MessageRef line(@Arg("from") Object from, @Arg("until") Object until, @Arg("source") Object source);

            @Name("Revoked")
            MessageRef revoked(@Arg("from") Object from, @Arg("until") Object until, @Arg("source") Object source);
        }

        Purchases purchases();

        @Name("Purchases")
        interface Purchases {

            @Name("Header")
            MessageRef header();

            @Name("None")
            MessageRef none();

            @Name("Line")
            MessageRef line(@Arg("reference") Object reference, @Arg("days") Object days, @Arg("amount") Object amount, @Arg("status") Object status);
        }

        @Key("revoked")
        Revoked revokedSection();

        @Name("Revoked")
        interface Revoked {

            @Name("One")
            MessageRef one();

            @Name("None")
            MessageRef none();
        }

        Settle settle();

        @Name("Settle")
        interface Settle {

            @Name("Unknown")
            MessageRef unknown(@Arg("reference") Object reference);

            @Name("Not open")
            MessageRef notOpen(@Arg("reference") Object reference, @Arg("status") Object status);

            @Name("Booked")
            MessageRef booked(@Arg("reference") Object reference, @Arg("days") Object days, @Arg("until") Object until);
        }

        Messages messages();

        @Name("Messages")
        interface Messages {

            @Name("Reloaded")
            MessageRef reloaded();

            @Name("Reloaded with unknown")
            MessageRef reloadedWithUnknown(@Arg("keys") Object keys);

            @Name("Reload failed")
            MessageRef reloadFailed();
        }
    }

    Announce announce();

    @Name("Announce")
    interface Announce {

        @Name("Posted")
        MessageRef posted(@Arg("language") Object language);

        @Name("Phase")
        MessageRef phase(@Arg("phase") Object phase, @Arg("previous") Object previous);

        @Name("No channel")
        MessageRef noChannel(@Arg("language") Object language);
    }

    Update update();

    @Name("Update")
    interface Update {

        @Name("Asked")
        MessageRef asked();

        @Name("Started")
        MessageRef started(@Arg("seconds") Object seconds);

        @Name("Cancelled")
        MessageRef cancelled();

        @Name("Too late")
        MessageRef tooLate();

        @Name("Write failed")
        MessageRef writeFailed();

        @Name("Busy")
        MessageRef busy();

        @Name("Already down")
        MessageRef alreadyDown(@Arg("services") Object services);

        @Name("Truncated")
        MessageRef truncated(@Arg("lines") Object lines);

        @Name("Failed")
        MessageRef failed();

        @Name("Gone")
        MessageRef gone();

        @Name("Timeout")
        MessageRef timeout(@Arg("status") Object status);

        @Name("Stopped by")
        MessageRef stoppedBy(@Arg("reason") Object reason);

        @Name("Change")
        MessageRef change(@Arg("artefact") Object artefact, @Arg("from") Object from, @Arg("to") Object to);

        @Name("Detail")
        MessageRef detail(@Arg("detail") Object detail);

        @Name("Note")
        MessageRef note(@Arg("note") Object note);

        @Name("Interaction failed")
        MessageRef interactionFailed();

        Down down();

        @Name("Down")
        interface Down {

            @Name("Asked")
            MessageRef asked(@Arg("service") Object service, @Arg("seconds") Object seconds);
        }

        Start start();

        @Name("Start")
        interface Start {

            @Name("Asked")
            MessageRef asked();
        }

        /** The headline for a stage. */
        default MessageRef stage(final UpdateReport.Stage stage) {
            return switch (stage) {
                case RESOLVING -> stage().resolving();
                case PLANNED -> stage().planned();
                case COUNTDOWN -> stage().countdown();
                case STOPPING -> stage().stopping();
                case BACKING_UP -> stage().backingUp();
                case INSTALLING -> stage().installing();
                case STARTING -> stage().starting();
                case VERIFYING -> stage().verifying();
                case DONE -> stage().done();
                case NOTHING_TO_DO -> stage().nothingToDo();
                case FAILED -> stage().failed();
                case CANCELLED -> stage().cancelled();
            };
        }

        /** A service's state on its own, for a column that already names the service. */
        default MessageRef state(final UpdateReport.State state) {
            return switch (state) {
                case UNCHANGED -> state().unchanged();
                case PLANNED -> state().planned();
                case STOPPED -> state().stopped();
                case INSTALLED -> state().installed();
                case SAVED -> state().saved();
                case STARTING -> state().starting();
                case HEALTHY -> state().healthy();
                case FAILED -> state().failed();
            };
        }

        /** A service's state with its name, for chat. */
        default MessageRef line(final UpdateReport.State state, final Object service) {
            return switch (state) {
                case UNCHANGED -> line().unchanged(service);
                case PLANNED -> line().planned(service);
                case STOPPED -> line().stopped(service);
                case INSTALLED -> line().installed(service);
                case SAVED -> line().saved(service);
                case STARTING -> line().starting(service);
                case HEALTHY -> line().healthy(service);
                case FAILED -> line().failed(service);
            };
        }

        /** The heading of a run, by what was asked for. */
        default MessageRef title(final UpdateKind kind) {
            return switch (kind) {
                case REPORT -> title().report();
                case APPLY -> title().apply();
                case UPDATE -> title().update();
                case RESTART -> title().restart();
                case BACKUP -> title().backup();
                case DOWN -> title().down();
                case START -> title().start();
            };
        }

        Stage stage();

        @Name("Stage")
        interface Stage {

            @Name("Resolving")
            @Key("RESOLVING")
            MessageRef resolving();

            @Name("Planned")
            @Key("PLANNED")
            MessageRef planned();

            @Name("Countdown")
            @Key("COUNTDOWN")
            MessageRef countdown();

            @Name("Stopping")
            @Key("STOPPING")
            MessageRef stopping();

            @Name("Backing up")
            @Key("BACKING_UP")
            MessageRef backingUp();

            @Name("Installing")
            @Key("INSTALLING")
            MessageRef installing();

            @Name("Starting")
            @Key("STARTING")
            MessageRef starting();

            @Name("Verifying")
            @Key("VERIFYING")
            MessageRef verifying();

            @Name("Done")
            @Key("DONE")
            MessageRef done();

            @Name("Nothing to do")
            @Key("NOTHING_TO_DO")
            MessageRef nothingToDo();

            @Name("Failed")
            @Key("FAILED")
            MessageRef failed();

            @Name("Cancelled")
            @Key("CANCELLED")
            MessageRef cancelled();
        }

        State state();

        @Name("State")
        interface State {

            @Name("Unchanged")
            @Key("UNCHANGED")
            MessageRef unchanged();

            @Name("Planned")
            @Key("PLANNED")
            MessageRef planned();

            @Name("Stopped")
            @Key("STOPPED")
            MessageRef stopped();

            @Name("Installed")
            @Key("INSTALLED")
            MessageRef installed();

            @Name("Saved")
            @Key("SAVED")
            MessageRef saved();

            @Name("Starting")
            @Key("STARTING")
            MessageRef starting();

            @Name("Healthy")
            @Key("HEALTHY")
            MessageRef healthy();

            @Name("Failed")
            @Key("FAILED")
            MessageRef failed();
        }

        Line line();

        @Name("Line")
        interface Line {

            @Name("Unchanged")
            @Key("UNCHANGED")
            MessageRef unchanged(@Arg("service") Object service);

            @Name("Planned")
            @Key("PLANNED")
            MessageRef planned(@Arg("service") Object service);

            @Name("Stopped")
            @Key("STOPPED")
            MessageRef stopped(@Arg("service") Object service);

            @Name("Installed")
            @Key("INSTALLED")
            MessageRef installed(@Arg("service") Object service);

            @Name("Saved")
            @Key("SAVED")
            MessageRef saved(@Arg("service") Object service);

            @Name("Starting")
            @Key("STARTING")
            MessageRef starting(@Arg("service") Object service);

            @Name("Healthy")
            @Key("HEALTHY")
            MessageRef healthy(@Arg("service") Object service);

            @Name("Failed")
            @Key("FAILED")
            MessageRef failed(@Arg("service") Object service);
        }

        @Key("change")
        Change changeSection();

        @Name("Change")
        interface Change {

            @Name("New")
            @Key("new")
            MessageRef newMessage(@Arg("artefact") Object artefact, @Arg("to") Object to);

            @Name("Unsupported")
            MessageRef unsupported(@Arg("artefact") Object artefact);
        }

        Waiting waiting();

        @Name("Waiting")
        interface Waiting {

            @Name("Check")
            MessageRef check();

            @Name("Now")
            MessageRef now();
        }

        Countdown countdown();

        @Name("Countdown")
        interface Countdown {

            @Name("Started")
            MessageRef started(@Arg("seconds") Object seconds);
        }

        Button button();

        @Name("Button")
        interface Button {

            @Name("Install")
            MessageRef install();

            @Name("Restart")
            MessageRef restart();

            @Name("Cancel")
            MessageRef cancel();
        }

        Title title();

        @Name("Title")
        interface Title {

            @Name("Report")
            @Key("REPORT")
            MessageRef report();

            @Name("Update")
            @Key("UPDATE")
            MessageRef update();

            @Name("Restart")
            @Key("RESTART")
            MessageRef restart();

            @Name("Backup")
            @Key("BACKUP")
            MessageRef backup();

            @Name("Apply")
            @Key("APPLY")
            MessageRef apply();

            @Name("Down")
            @Key("DOWN")
            MessageRef down();

            @Name("Start")
            @Key("START")
            MessageRef start();
        }
    }

    Backup backup();

    @Name("Backup")
    interface Backup {

        @Name("Started")
        MessageRef started(@Arg("seconds") Object seconds);
    }
}
