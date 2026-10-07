package eu.nordtal.season.hungergames;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.context.TeamContext;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.Key;
import eu.nordtal.season.messages.spec.MessageSpec;
import eu.nordtal.season.messages.spec.MessageSpecs;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.spec.Shown;
import eu.nordtal.season.messages.value.Action;
import java.time.Duration;

/**
 * Every message of the hunger-games bundle, one method per key.
 */
@MessageSpec("hunger-games")
@Shown(Display.CHAT)
public interface HungerGamesMessages {

    /** The one shared instance; it is stateless. */
    HungerGamesMessages MESSAGES = MessageSpecs.create(HungerGamesMessages.class);

    Hg hg();

    @Name("Hunger Games")
    interface Hg {

        Start start();

        Admin admin();

        @Name("Admin")
        interface Admin {

            @Name("Started")
            @Shown({Display.CHAT, Display.STEWARD})
            MessageRef started(@Arg("count") int count);

            @Name("Ready status header")
            MessageRef readyHeader();

            @Name("Ready status line")
            MessageRef readyLine(@Arg("team") TeamContext team, @Arg("ready") boolean ready);
        }

        @Name("Start")
        interface Start {

            @Name("Countdown")
            MessageRef countdown(@Arg("seconds") long seconds);

            @Name("Released")
            MessageRef released(@Arg("seconds") long seconds);
        }

        Lobby lobby();

        @Name("Lobby")
        interface Lobby {

            @Name("Rules")
            MessageRef rules();

            @Name("Broadcast")
            MessageRef broadcast(@Arg("ready") long ready, @Arg("total") long total, @Arg("confirm") Action confirm);

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
            MessageRef pointLost(@Arg("label") String label);
        }

        Border border();

        @Name("Border")
        interface Border {

            @Name("Shrink started")
            MessageRef shrinkStarted(@Arg("target") long target, @Arg("seconds") long seconds);

            @Name("Passive shrink started")
            MessageRef passiveShrinkStarted();
        }

        Win win();

        @Name("Win")
        interface Win {

            @Name("Player")
            MessageRef player(@Arg("winner") PlayerContext winner);

            @Name("Tie broken")
            MessageRef tieBroken(
                    @Arg("winner") PlayerContext winner,
                    @Arg("winnerKills") int winnerKills,
                    @Arg("loserKills") int loserKills);

            @Name("No winner")
            MessageRef noWinner(@Arg("kills") int kills);

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
            MessageRef kills(@Arg("player") PlayerContext player, @Arg("kills") int kills);

            @Name("Footer")
            MessageRef footer();
        }

        Hud hud();

        @Name("HUD")
        @Shown(Display.BOSS_BAR)
        interface Hud {

            @Name("Alive")
            MessageRef alive(@Arg("alive") int alive);

            @Name("Dead")
            MessageRef dead(@Arg("dead") int dead);

            @Name("Loot")
            MessageRef loot(@Arg("time") Duration time);

            @Name("Loot none")
            MessageRef lootNone();

            @Name("Border shrinking")
            MessageRef borderShrinking(@Arg("time") Duration time, @Arg("distance") long distance);

            @Name("Border stable")
            MessageRef borderStable();
        }

        Death death();

        @Name("Death")
        interface Death {

            @Name("Body")
            MessageRef body(@Arg("player") PlayerContext player);

            @Key("body")
            Body bodySection();

            @Name("Body")
            interface Body {

                @Name("By")
                MessageRef by(@Arg("player") PlayerContext player, @Arg("killer") PlayerContext killer);
            }
        }
    }
}
