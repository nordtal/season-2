package eu.nordtal.season.proxy;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.Key;
import eu.nordtal.season.messages.spec.MessageSpec;
import eu.nordtal.season.messages.spec.MessageSpecs;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.spec.Shown;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.proxy.ping.ServerListContext;

/** Every message of the proxy bundle, one method per key. */
@MessageSpec("proxy")
public interface ProxyMessages {

    ProxyMessages MESSAGES = MessageSpecs.create(ProxyMessages.class);

    Gate gate();

    @Name("Gate")
    @Shown(Display.KICK_SCREEN)
    interface Gate {

        @Name("Not linked")
        MessageRef notLinked(@Arg("code") String code);

        @Name("Not member")
        MessageRef notMember();

        @Name("Unlinked")
        MessageRef unlinked();

        @Name("No access")
        MessageRef noAccess();

        @Name("Maintenance")
        MessageRef maintenance();

        @Name("No server")
        MessageRef noServer();

        @Name("Trouble")
        MessageRef trouble();

        @Name("Connection lost")
        MessageRef connectionLost();

        @Name("Misconfigured")
        MessageRef misconfigured();

        @Name("Restarting")
        MessageRef restarting();

        @Name("Full")
        MessageRef full(@Arg("online") long online, @Arg("max") long max);

        @Name("Countdown")
        MessageRef countdown(@Arg("countdown") @Example("countdown.imminent") MessageRef countdown);

        @Key("not-linked")
        NotLinked notLinkedSection();

        @Name("Not linked")
        interface NotLinked {

            @Name("Invite")
            MessageRef invite(@Arg("invite") String invite);
        }

        @Key("not-member")
        NotMember notMemberSection();

        @Name("Not member")
        interface NotMember {

            @Name("Invite")
            MessageRef invite(@Arg("invite") String invite);
        }

        @Key("no-access")
        NoAccess noAccessSection();

        @Name("No access")
        interface NoAccess {

            @Name("Invite")
            MessageRef invite(@Arg("invite") String invite);
        }

        Expiry expiry();

        @Name("Expiry")
        interface Expiry {

            @Name("Warning")
            MessageRef warning(@Arg("minutes") long minutes);

            @Name("Expired")
            MessageRef expired();
        }

        PreLaunch preLaunch();

        @Name("Pre launch")
        interface PreLaunch {

            @Name("Buy")
            MessageRef buy();

            @Name("Ready")
            MessageRef ready();
        }

        @Key("countdown")
        GateCountdown countdownSection();

        @Name("Countdown")
        interface GateCountdown {

            @Name("Unknown")
            MessageRef unknown();
        }
    }

    Pack pack();

    @Name("Pack")
    @Shown(Display.KICK_SCREEN)
    interface Pack {

        @Name("Prompt")
        MessageRef prompt();

        @Name("Declined")
        MessageRef declined();

        @Name("Failed download")
        MessageRef failedDownload();

        @Name("Invalid URL")
        MessageRef invalidUrl();

        @Name("Timeout")
        MessageRef timeout();
    }

    Restart restart();

    @Name("Restart")
    interface Restart {

        @Name("Tick")
        MessageRef tick(@Arg("seconds") long seconds);

        @Name("Cancelled")
        MessageRef cancelled(@Arg("occasion") @Example("restart.occasion.update") MessageRef occasion);

        @Name("Failed")
        MessageRef failed(@Arg("occasion") @Example("restart.occasion.update") MessageRef occasion);

        @Name("Voice")
        MessageRef voice();

        RestartCountdown countdown();

        @Name("Countdown")
        interface RestartCountdown {

            @Name("Update")
            MessageRef update(
                    @Arg("what") @Example("restart.what.network") MessageRef what, @Arg("seconds") long seconds);

            @Name("Recreate")
            MessageRef recreate(
                    @Arg("what") @Example("restart.what.network") MessageRef what, @Arg("seconds") long seconds);

            @Name("Backup")
            MessageRef backup(
                    @Arg("what") @Example("restart.what.network") MessageRef what, @Arg("seconds") long seconds);

            @Name("Down")
            MessageRef down(
                    @Arg("what") @Example("restart.what.network") MessageRef what, @Arg("seconds") long seconds);

            @Name("Maintenance")
            MessageRef maintenance(@Arg("seconds") long seconds);
        }

        Fate fate();

        @Name("Fate")
        interface Fate {

            @Name("Reconnect")
            MessageRef reconnect();

            @Name("Waiting room")
            MessageRef waitingRoom();

            @Name("Disconnect")
            MessageRef disconnect();
        }

        What what();

        @Name("What")
        interface What {

            @Name("Network")
            MessageRef network();

            @Name("SMP")
            MessageRef smp();

            @Name("Limbo")
            MessageRef limbo();

            @Name("Hunger Games")
            MessageRef hungerGames();

            @Name("Proxy")
            MessageRef proxy();

            @Name("Any other service")
            MessageRef other(@Arg("service") String service);
        }

        Occasion occasion();

        @Name("Occasion")
        interface Occasion {

            @Name("Update")
            MessageRef update();

            @Name("Recreate")
            MessageRef recreate();

            @Name("Backup")
            MessageRef backup();

            @Name("Down")
            MessageRef down();

            @Name("Maintenance")
            MessageRef maintenance();
        }

        Now now();

        @Name("Now")
        interface Now {

            @Name("Update")
            MessageRef update(@Arg("what") @Example("restart.what.network") MessageRef what);

            @Name("Recreate")
            MessageRef recreate(@Arg("what") @Example("restart.what.network") MessageRef what);

            @Name("Backup")
            MessageRef backup(@Arg("what") @Example("restart.what.network") MessageRef what);

            @Name("Down")
            MessageRef down(@Arg("what") @Example("restart.what.network") MessageRef what);

            @Name("Maintenance")
            MessageRef maintenance();
        }
    }

    @Key("return")
    Return returnSection();

    @Name("Return")
    interface Return {

        @Name("Waiting room")
        MessageRef waitingRoom(@Arg("what") @Example("restart.what.network") MessageRef what);

        @Name("Countdown")
        MessageRef countdown(@Arg("seconds") long seconds);

        @Name("Now")
        MessageRef now();
    }

    NetworkCountdown countdown();

    @Name("Countdown")
    interface NetworkCountdown {

        @Name("Days")
        MessageRef days(@Arg("days") long days, @Arg("hours") long hours);

        @Name("Hours")
        MessageRef hours(@Arg("hours") long hours, @Arg("minutes") long minutes);

        @Name("Minutes")
        MessageRef minutes(@Arg("minutes") long minutes);

        @Name("Imminent")
        MessageRef imminent();

        @Name("Unknown")
        MessageRef unknown();
    }

    Motd motd();

    @Name("Server list text")
    @Shown(Display.SERVER_LIST)
    interface Motd {

        @Name("Before the opening")
        MessageRef preLaunch(@Arg("list") ServerListContext list);

        @Name("Before the event")
        MessageRef preEvent(@Arg("list") ServerListContext list);

        @Name("Event")
        MessageRef startEvent(@Arg("list") ServerListContext list);

        @Name("SMP")
        MessageRef smp(@Arg("list") ServerListContext list);

        @Name("Maintenance")
        MessageRef maintenance();

        @Name("Misconfigured")
        MessageRef misconfigured();
    }

    Chat chat();

    @Name("Chat")
    interface Chat {

        @Name("No partner")
        MessageRef noPartner();

        @Name("Failed")
        MessageRef failed();

        Msg msg();

        @Name("Private message")
        interface Msg {

            @Name("Sent")
            MessageRef sent(
                    @Arg("flag") String flag,
                    @Arg("partner") PlayerContext partner,
                    @Arg("admin") String admin,
                    @Arg("message") String message);

            @Name("Received")
            MessageRef received(
                    @Arg("flag") String flag,
                    @Arg("partner") PlayerContext partner,
                    @Arg("admin") String admin,
                    @Arg("message") String message);

            @Name("Self")
            MessageRef self();
        }
    }

    Command command();

    @Name("Command")
    interface Command {

        @Name("Unknown")
        MessageRef unknown();

        @Name("Not from console")
        MessageRef notFromConsole();

        @Name("Player offline")
        MessageRef playerOffline();

        Help help();

        @Name("Help")
        interface Help {

            @Name("Usage")
            MessageRef usage(@Arg("usage") String usage);

            @Name("What")
            MessageRef what(@Arg("what") String what);
        }

        DescribeMessages describe();

        @Name("Command help")
        interface DescribeMessages {

            @Name("/msg")
            MessageRef msg();

            @Name("/whisper")
            MessageRef whisper();

            @Name("/r")
            MessageRef r();
        }
    }

    InfoCommands info();

    @Name("Info commands")
    interface InfoCommands {

        @Name("Discord")
        MessageRef discord(@Arg("invite") String invite);

        @Name("Rules")
        MessageRef rules(@Arg("invite") String invite);
    }
}
