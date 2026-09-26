package eu.nordtal.s2.hungergames;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.context.PlayerContext;
import eu.nordtal.s2.common.message.context.TeamContext;
import eu.nordtal.s2.common.message.spec.Arg;
import eu.nordtal.s2.common.message.spec.Display;
import eu.nordtal.s2.common.message.spec.Key;
import eu.nordtal.s2.common.message.spec.MessageSpec;
import eu.nordtal.s2.common.message.spec.MessageSpecs;
import eu.nordtal.s2.common.message.spec.Name;
import eu.nordtal.s2.common.message.spec.Shown;
import net.kyori.adventure.text.Component;

/**
 * Every message of the hunger-games bundle, one method per key.
 */
@MessageSpec("hunger-games")
public interface HungerGamesMessages {

    /** The messages; stateless, so one instance serves every caller. */
    HungerGamesMessages MESSAGES = MessageSpecs.create(HungerGamesMessages.class);

    Hg hg();

    @Name("Hunger Games")
    interface Hg {

        Start start();

        @Name("Start")
        interface Start {

            @Name("Countdown")
            MessageRef countdown(@Arg("seconds") Object seconds);

            @Name("Released")
            MessageRef released(@Arg("seconds") Object seconds);
        }

        Lobby lobby();

        @Name("Lobby")
        interface Lobby {

            @Name("Broadcast")
            MessageRef broadcast(@Arg("ready") Object ready, @Arg("total") Object total, @Arg("_link") Component link);

            @Name("Ready link")
            MessageRef readyLink();

            @Name("Ready set")
            MessageRef readySet();

            @Name("Not registered")
            MessageRef notRegistered();

            @Name("Map missing")
            MessageRef mapMissing();
        }

        Team team();

        @Name("Team")
        interface Team {

            @Name("Demoted")
            MessageRef demoted(@Arg("team") TeamContext team);
        }

        Loot loot();

        @Name("Loot")
        interface Loot {

            @Name("Refill")
            MessageRef refill();

            @Name("Point lost")
            MessageRef pointLost(@Arg("label") Object label);
        }

        Border border();

        @Name("Border")
        interface Border {

            @Name("Shrink started")
            MessageRef shrinkStarted(@Arg("target") Object target, @Arg("seconds") Object seconds);

            @Name("Passive shrink started")
            MessageRef passiveShrinkStarted();
        }

        Win win();

        @Name("Win")
        interface Win {

            @Name("Player")
            MessageRef player(@Arg("icon") Object icon, @Arg("winner") PlayerContext winner);

            @Name("Tie broken")
            MessageRef tieBroken(
                    @Arg("winner") PlayerContext winner,
                    @Arg("winnerKills") Object winnerKills,
                    @Arg("loserKills") Object loserKills);

            @Name("No winner")
            MessageRef noWinner(@Arg("kills") Object kills);

            @Name("Same team final two")
            MessageRef sameTeamFinalTwo();
        }

        Ceremony ceremony();

        @Name("Ceremony")
        interface Ceremony {

            @Name("Header")
            MessageRef header();

            @Name("No winner")
            MessageRef noWinner();

            @Name("Kills")
            MessageRef kills(@Arg("player") PlayerContext player, @Arg("kills") Object kills);

            @Name("Footer")
            MessageRef footer();
        }

        Hud hud();

        @Name("HUD")
        @Shown(Display.BOSS_BAR)
        interface Hud {

            @Name("Players")
            MessageRef players(@Arg("alive") Object alive, @Arg("dead") Object dead);

            @Name("Loot")
            MessageRef loot(@Arg("time") Object time);

            @Name("Loot none")
            MessageRef lootNone();

            @Name("Border shrinking")
            MessageRef borderShrinking(@Arg("time") Object time, @Arg("distance") Object distance);

            @Name("Border stable")
            MessageRef borderStable();
        }

        Death death();

        @Name("Death")
        interface Death {

            @Name("Body")
            MessageRef body(@Arg("icon") Object icon, @Arg("_player") Component player);

            @Key("body")
            Body bodySection();

            @Name("Body")
            interface Body {

                @Name("By")
                MessageRef by(
                        @Arg("icon") Object icon, @Arg("_player") Component player, @Arg("_killer") Component killer);
            }
        }
    }

    Tab tab();

    @Name("Tab")
    @Shown(Display.TAB_LIST)
    interface Tab {

        @Name("Header")
        MessageRef header(@Arg("logo") Object logo);

        @Name("Footer")
        MessageRef footer(@Arg("online") Object online, @Arg("max") Object max);
    }
}
