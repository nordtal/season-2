package eu.nordtal.season.smp;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.context.DiscordMemberContext;
import eu.nordtal.season.messages.context.MilestoneContext;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.messages.spec.Key;
import eu.nordtal.season.messages.spec.MessageSpec;
import eu.nordtal.season.messages.spec.MessageSpecs;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.spec.Shown;
import eu.nordtal.season.messages.value.GameContent;
import eu.nordtal.season.messages.value.Money;
import eu.nordtal.season.smp.world.WorldRole;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@MessageSpec("smp")
@Shown(Display.CHAT)
public interface SmpMessages {

    SmpMessages MESSAGES = MessageSpecs.create(SmpMessages.class);

    Smp smp();

    @Name("SMP")
    interface Smp {

        World world();

        default MessageRef world(final WorldRole role) {
            return switch (role) {
                case NORDTAL -> world().nordtal();
                case NETHER -> world().nether();
                case END -> world().end();
            };
        }

        @Name("World")
        @Shown({Display.GUI, Display.BOSS_BAR, Display.CHAT})
        interface World {

            @Name("Nordtal")
            MessageRef nordtal();

            @Name("Nether")
            MessageRef nether();

            @Name("End")
            MessageRef end();
        }

        Portal portal();

        @Name("Portal")
        interface Portal {

            @Name("Nether locked")
            MessageRef netherLocked();

            @Name("End locked")
            MessageRef endLocked();
        }

        Protect protect();

        @Name("Protect")
        @Shown(Display.ACTION_BAR)
        interface Protect {

            @Name("Denied")
            MessageRef denied();
        }

        Failure error();

        Access access();

        @Name("Access")
        interface Access {

            @Name("Active")
            MessageRef active(@Arg("until") Instant until);

            @Name("Expired")
            MessageRef expired(@Arg("since") Instant since);

            @Name("Linked")
            MessageRef linked(@Arg("player") PlayerContext player, @Arg("discord") DiscordMemberContext discord);

            @Name("Never")
            MessageRef never();

            @Name("No payment")
            MessageRef noPayment();

            @Name("Payment")
            MessageRef payment(
                    @Arg("reference") String reference,
                    @Arg("days") long days,
                    @Arg("amount") Money amount,
                    @Arg("since") Instant since);

            @Name("Payment unstarted")
            MessageRef paymentUnstarted(
                    @Arg("reference") String reference, @Arg("days") long days, @Arg("since") Instant since);

            @Name("Unlinked")
            MessageRef unlinked(@Arg("player") PlayerContext player);
        }

        Admin admin();

        @Name("Admin")
        interface Admin {

            @Name("Aura changed")
            MessageRef auraChanged(@Arg("player") PlayerContext player, @Arg("delta") long delta);

            @Name("Aura unknown")
            MessageRef auraUnknown(@Arg("player") PlayerContext player, @Arg("delta") long delta);

            @Name("Milestone unlocked")
            @Shown({Display.CHAT, Display.STEWARD})
            MessageRef milestoneUnlocked(@Arg("key") String key);

            @Name("Player offline")
            MessageRef playerOffline();

            @Name("Name tags redrawn")
            MessageRef nameTagsRedrawn();

            @Name("Target unlinked")
            MessageRef targetUnlinked(@Arg("player") PlayerContext player);

            @Name("Objective completed")
            @Shown({Display.CHAT, Display.STEWARD})
            MessageRef objectiveCompleted(@Arg("key") String key, @Arg("milestone") MilestoneContext milestone);
        }

        @Name("Error")
        interface Failure {

            @Name("No account link")
            MessageRef noAccountLink();
        }

        Hud hud();

        @Name("HUD")
        @Shown(Display.BOSS_BAR)
        interface Hud {

            @Name("Milestone")
            MessageRef milestone(@Arg("milestone") MilestoneContext milestone);

            @Name("Progress")
            MessageRef progress(@Arg("percent") double percent);

            @Name("Distance")
            MessageRef distance(@Arg("blocks") long blocks);

            @Name("Navigate other world")
            MessageRef navigateOtherWorld();
        }

        Navigate navigate();

        @Name("Navigate")
        @Shown(Display.GUI)
        interface Navigate {

            @Name("Stopped")
            @Shown(Display.CHAT)
            MessageRef stopped();

            @Name("Started")
            @Shown(Display.CHAT)
            MessageRef started(@Arg("target") String target);

            @Name("Arrived")
            @Shown(Display.CHAT)
            MessageRef arrived(@Arg("target") String target);

            @Name("Nothing to cancel")
            @Shown(Display.CHAT)
            MessageRef nothingToCancel();

            @Name("World spawn")
            @Shown({Display.GUI, Display.CHAT, Display.BOSS_BAR})
            MessageRef worldSpawn();

            @Name("Last death")
            @Shown({Display.GUI, Display.CHAT, Display.BOSS_BAR})
            MessageRef lastDeath();
        }

        Smp.Poi poi();

        @Name("Places")
        interface Poi {

            @Name("Added")
            MessageRef added(@Arg("name") String name);

            @Name("Removed")
            MessageRef removed(@Arg("name") String name);

            @Name("Duplicate")
            MessageRef duplicate(@Arg("name") String name);

            @Name("Not found")
            MessageRef notFound(@Arg("name") String name);

            @Name("Not yours")
            MessageRef notYours();

            @Name("Bad name")
            MessageRef badName(@Arg("max") long max);
        }

        Board board();

        @Name("Board")
        @Shown(Display.HOLOGRAM)
        interface Board {

            Board.Objective objective();

            @Name("Objective")
            interface Objective {

                @Name("Title")
                MessageRef title();

                @Name("Finished")
                MessageRef finished();

                @Name("Milestone")
                MessageRef milestone(@Arg("milestone") MilestoneContext milestone);

                @Name("Row")
                MessageRef row(
                        @Arg("objective") String objective,
                        @Arg("bar") String bar,
                        @Arg("amount") long amount,
                        @Arg("target") long target);

                @Name("Row done")
                MessageRef rowDone(
                        @Arg("objective") String objective,
                        @Arg("bar") String bar,
                        @Arg("amount") long amount,
                        @Arg("target") long target);
            }

            Board.Aura aura();

            @Name("Aura")
            interface Aura {

                @Name("Title")
                MessageRef title();

                @Name("Empty")
                MessageRef empty();

                @Name("Row")
                MessageRef row(@Arg("place") long place, @Arg("player") PlayerContext player, @Arg("aura") long aura);

                @Name("Row zero")
                MessageRef rowZero(
                        @Arg("place") long place, @Arg("player") PlayerContext player, @Arg("aura") long aura);
            }
        }

        Smp.Aura aura();

        @Name("Aura")
        interface Aura {

            @Name("Death")
            MessageRef death(@Arg("aura") long aura);

            @Name("Advancement")
            MessageRef advancement(@Arg("aura") long aura);
        }

        Smp.Objective objective();

        @Name("Objective")
        interface Objective {

            @Name("Completed")
            MessageRef completed(@Arg("objective") String objective);
        }

        Smp.Milestone milestone();

        /** Returns the bundled name of milestone {@code key}, or empty if only the {@code milestones} group has it. */
        default Optional<MessageRef> milestoneName(final String key) {
            return Optional.ofNullable(
                    switch (key) {
                        case "waiting" -> milestone().waiting();
                        case "departure" -> milestone().departure();
                        case "foothold" -> milestone().foothold();
                        case "settlement" -> milestone().settlement();
                        case "nether" -> milestone().nether();
                        case "end" -> milestone().end();
                        case "expanse" -> milestone().expanse();
                        case "frontier" -> milestone().frontier();
                        default -> null;
                    });
        }

        @Name("Milestone")
        @Shown({Display.BOSS_BAR, Display.HOLOGRAM, Display.GUI, Display.CHAT, Display.TITLE, Display.DISCORD_MESSAGE})
        interface Milestone {

            @Name("Waiting")
            MessageRef waiting();

            @Name("Departure")
            MessageRef departure();

            @Name("Foothold")
            MessageRef foothold();

            @Name("Settlement")
            MessageRef settlement();

            @Name("Nether")
            MessageRef nether();

            @Name("End")
            MessageRef end();

            @Name("Expanse")
            MessageRef expanse();

            @Name("Frontier")
            MessageRef frontier();

            @Name("Completed")
            MessageRef completed(@Arg("milestone") MilestoneContext milestone);
        }

        Ceremony ceremony();

        @Name("Ceremony")
        @Shown(Display.TITLE)
        interface Ceremony {

            @Name("Title")
            MessageRef title(@Arg("milestone") MilestoneContext milestone);

            @Name("Subtitle")
            @Shown(Display.SUBTITLE)
            MessageRef subtitle();
        }

        Grave grave();

        @Name("Grave")
        @Shown(Display.GUI)
        interface Grave {

            @Name("Experience")
            MessageRef experience(@Arg("experience") long experience);

            @Name("Hologram")
            @Shown(Display.HOLOGRAM)
            MessageRef hologram(@Arg("left") Duration left);
        }

        Duel duel();

        @Name("Duel")
        interface Duel {

            @Name("Waiting")
            MessageRef waiting();

            @Name("Queued")
            MessageRef queued();

            @Name("Countdown")
            MessageRef countdown(@Arg("seconds") long seconds);

            @Name("Go")
            MessageRef go();

            @Name("Won")
            MessageRef won(@Arg("aura") long aura);

            @Name("Lost")
            MessageRef lost(@Arg("aura") long aura);

            @Name("Interrupted")
            MessageRef interrupted();

            @Key("won")
            Won wonSection();

            @Name("Won")
            @Shown(Display.TITLE)
            interface Won {

                @Name("Title")
                MessageRef title();

                @Name("Subtitle")
                @Shown(Display.SUBTITLE)
                MessageRef subtitle(@Arg("aura") long aura);
            }

            @Key("lost")
            Lost lostSection();

            @Name("Lost")
            @Shown(Display.TITLE)
            interface Lost {

                @Name("Title")
                MessageRef title();

                @Name("Subtitle")
                @Shown(Display.SUBTITLE)
                MessageRef subtitle(@Arg("aura") long aura);
            }
        }

        Wheel wheel();

        @Name("Wheel")
        @Shown(Display.GUI)
        interface Wheel {

            @Name("Won")
            @Shown(Display.CHAT)
            MessageRef won(@Arg("amount") int amount, @Arg("item") GameContent item);

            @Name("None")
            @Shown(Display.CHAT)
            MessageRef none();

            @Name("Available")
            MessageRef available();

            @Name("Broken prize")
            @Shown(Display.CHAT)
            MessageRef brokenPrize();

            @Key("none")
            None noneSection();

            @Name("None")
            @Shown(Display.CHAT)
            interface None {

                @Name("One")
                MessageRef one();

                @Name("Many")
                MessageRef many(@Arg("extras") long extras);
            }

            @Key("available")
            Available availableSection();

            @Name("Available")
            @Shown(Display.CHAT)
            interface Available {

                @Name("One")
                MessageRef one();

                @Name("Many")
                MessageRef many(@Arg("count") long count);
            }
        }

        Objectives objectives();

        @Name("Objectives")
        @Shown(Display.GUI)
        interface Objectives {

            @Name("None")
            MessageRef none();

            @Name("NPC name")
            @Shown(Display.HOLOGRAM)
            MessageRef npcName();
        }

        Handin handin();

        @Name("Handin")
        @Shown(Display.GUI)
        interface Handin {

            @Name("Accepted")
            @Shown(Display.CHAT)
            MessageRef accepted(@Arg("amount") long amount);

            @Name("Nothing wanted")
            @Shown(Display.CHAT)
            MessageRef nothingWanted();

            @Name("Nothing credited")
            @Shown(Display.CHAT)
            MessageRef nothingCredited();
        }

        Settings settings();

        /** The names that Steward's settings show for the choices of the {@code milestones} group. */
        @Name("Settings")
        @Shown(Display.STEWARD)
        interface Settings {

            Unlock unlock();

            @Name("Unlocks")
            interface Unlock {

                @Name("Border")
                MessageRef border();

                @Name("Nether")
                MessageRef nether();

                @Name("End")
                MessageRef end();

                @Name("Nothing")
                MessageRef nothing();
            }

            ObjectiveType objectiveType();

            @Name("Objective type")
            interface ObjectiveType {

                @Name("Hand-in")
                MessageRef handIn();

                @Name("Statistic")
                MessageRef statistic();

                @Name("Advancement")
                MessageRef advancement();
            }
        }
    }

    Command command();

    @Name("Command")
    interface Command {

        DescribeMessages describe();

        @Name("Command help")
        interface DescribeMessages {

            DescribeMessages.Poi poi();

            @Name("Places")
            interface Poi {

                @Name("Add")
                MessageRef add();

                @Name("Remove")
                MessageRef remove();
            }
        }
    }
}
