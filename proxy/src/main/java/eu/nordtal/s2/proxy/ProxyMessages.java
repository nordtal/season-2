package eu.nordtal.s2.proxy;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.context.PlayerContext;
import eu.nordtal.s2.common.message.spec.Arg;
import eu.nordtal.s2.common.message.spec.Display;
import eu.nordtal.s2.common.message.spec.Key;
import eu.nordtal.s2.common.message.spec.MessageSpec;
import eu.nordtal.s2.common.message.spec.MessageSpecs;
import eu.nordtal.s2.common.message.spec.Name;
import eu.nordtal.s2.common.message.spec.Shown;
import net.kyori.adventure.text.Component;

/**
 * Every message of the proxy bundle, one method per key.
 */
@MessageSpec("proxy")
public interface ProxyMessages {

    /** The messages; stateless, so one instance serves every caller. */
    ProxyMessages MESSAGES = MessageSpecs.create(ProxyMessages.class);

    Gate gate();

    @Name("Gate")
    @Shown(Display.KICK_SCREEN)
    interface Gate {

        @Name("Not linked")
        MessageRef notLinked(@Arg("code") Object code);

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
        MessageRef full(@Arg("online") Object online, @Arg("max") Object max);

        @Name("Countdown")
        MessageRef countdown(@Arg("countdown") Object countdown);

        @Key("not-linked")
        NotLinked notLinkedSection();

        @Name("Not linked")
        interface NotLinked {

            @Name("Invite")
            MessageRef invite(@Arg("invite") Object invite);
        }

        @Key("not-member")
        NotMember notMemberSection();

        @Name("Not member")
        interface NotMember {

            @Name("Invite")
            MessageRef invite(@Arg("invite") Object invite);
        }

        @Key("no-access")
        NoAccess noAccessSection();

        @Name("No access")
        interface NoAccess {

            @Name("Invite")
            MessageRef invite(@Arg("invite") Object invite);
        }

        Expiry expiry();

        @Name("Expiry")
        interface Expiry {

            @Name("Warning")
            MessageRef warning(@Arg("minutes") Object minutes);

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
        Countdown countdownSection();

        @Name("Countdown")
        interface Countdown {

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
        MessageRef tick(@Arg("seconds") Object seconds);

        @Name("Cancelled")
        MessageRef cancelled(@Arg("occasion") Object occasion);

        @Name("Failed")
        MessageRef failed(@Arg("occasion") Object occasion);

        @Name("Voice")
        MessageRef voice();

        Countdown countdown();

        @Name("Countdown")
        interface Countdown {

            @Name("Update")
            MessageRef update(@Arg("what") Object what, @Arg("seconds") Object seconds);

            @Name("Recreate")
            MessageRef recreate(@Arg("what") Object what, @Arg("seconds") Object seconds);

            @Name("Backup")
            MessageRef backup(@Arg("what") Object what, @Arg("seconds") Object seconds);

            @Name("Down")
            MessageRef down(@Arg("what") Object what, @Arg("seconds") Object seconds);

            @Name("Maintenance")
            MessageRef maintenance(@Arg("seconds") Object seconds);
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
            MessageRef update(@Arg("what") Object what);

            @Name("Recreate")
            MessageRef recreate(@Arg("what") Object what);

            @Name("Backup")
            MessageRef backup(@Arg("what") Object what);

            @Name("Down")
            MessageRef down(@Arg("what") Object what);

            @Name("Maintenance")
            MessageRef maintenance();
        }
    }

    @Key("return")
    Return returnSection();

    @Name("Return")
    interface Return {

        @Name("Waiting room")
        MessageRef waitingRoom(@Arg("what") Object what);

        @Name("Countdown")
        MessageRef countdown(@Arg("seconds") Object seconds);

        @Name("Now")
        MessageRef now();
    }

    Countdown countdown();

    @Name("Countdown")
    interface Countdown {

        @Name("Days")
        MessageRef days(@Arg("days") Object days, @Arg("hours") Object hours);

        @Name("Hours")
        MessageRef hours(@Arg("hours") Object hours, @Arg("minutes") Object minutes);

        @Name("Minutes")
        MessageRef minutes(@Arg("minutes") Object minutes);

        @Name("Imminent")
        MessageRef imminent();

        @Name("Unknown")
        MessageRef unknown();
    }

    Motd motd();

    @Name("Server list text")
    @Shown(Display.SERVER_LIST)
    interface Motd {

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
            MessageRef sent(@Arg("flag") Object flag, @Arg("partner") PlayerContext partner, @Arg("admin") Object admin, @Arg("_message") Component message);

            @Name("Received")
            MessageRef received(@Arg("flag") Object flag, @Arg("partner") PlayerContext partner, @Arg("admin") Object admin, @Arg("_message") Component message);

            @Name("Self")
            MessageRef self();
        }
    }

    Command command();

    @Name("Command")
    interface Command {

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

    Info info();

    @Name("Info commands")
    interface Info {

        @Name("Discord")
        MessageRef discord(@Arg("invite") Object invite);

        @Name("Rules")
        MessageRef rules(@Arg("invite") Object invite);
    }
}
