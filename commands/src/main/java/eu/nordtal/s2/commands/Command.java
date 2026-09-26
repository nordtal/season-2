package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.context.PlayerContext;
import eu.nordtal.s2.common.message.spec.Arg;
import eu.nordtal.s2.common.message.spec.Key;
import eu.nordtal.s2.common.message.spec.Name;

/** The messages every command shares, regardless of which one it is. */
@Name("Command")
public interface Command {

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
    MessageRef playerUnlinked(@Arg("player") PlayerContext player);

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
