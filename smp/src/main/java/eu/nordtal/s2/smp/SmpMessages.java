package eu.nordtal.s2.smp;

import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.context.DiscordMemberContext;
import eu.nordtal.s2.messages.context.MilestoneContext;
import eu.nordtal.s2.messages.context.PlayerContext;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.Display;
import eu.nordtal.s2.messages.spec.Key;
import eu.nordtal.s2.messages.spec.MessageSpec;
import eu.nordtal.s2.messages.spec.MessageSpecs;
import eu.nordtal.s2.messages.spec.Name;
import eu.nordtal.s2.messages.spec.Shown;
import eu.nordtal.s2.messages.value.GameContent;
import eu.nordtal.s2.messages.value.Money;
import eu.nordtal.s2.smp.world.WorldRole;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Every message of the smp bundle, one method per key. */
@MessageSpec("smp")
public interface SmpMessages {

    /** The messages; stateless, so one instance serves every caller. */
    SmpMessages MESSAGES = MessageSpecs.create(SmpMessages.class);

    Smp smp();

    @Name("SMP")
    interface Smp {

        World world();

        /** A world's name, by its role. */
        default MessageRef world(final WorldRole role) {
            return switch (role) {
                case NORDTAL -> world().nordtal();
                case NETHER -> world().nether();
                case END -> world().end();
            };
        }

        @Name("World")
        interface World {

            @Name("Nordtal")
            MessageRef nordtal();

            @Name("Nether")
            MessageRef nether();

            @Name("End")
            MessageRef end();
        }

        Balloon balloon();

        @Name("Balloon")
        @Shown(Display.GUI)
        interface Balloon {

            @Name("Title")
            MessageRef title();

            @Name("Open")
            MessageRef open();

            @Name("Here")
            MessageRef here();

            @Name("Locked")
            MessageRef locked(@Arg("milestone") MilestoneContext milestone);

            @Name("Locked hint")
            MessageRef lockedHint();

            @Name("Card")
            MessageRef card(@Arg("world") String world);

            @Name("Card locked")
            MessageRef cardLocked(@Arg("world") String world);

            @Name("Locked unknown")
            MessageRef lockedUnknown();

            @Name("Travelled")
            @Shown(Display.CHAT)
            MessageRef travelled(@Arg("world") String world);

            @Name("Unavailable")
            @Shown(Display.CHAT)
            MessageRef unavailable();
        }

        Portal portal();

        @Name("Portal")
        interface Portal {

            @Name("Nether locked")
            MessageRef netherLocked();

            @Name("End inactive")
            MessageRef endInactive();
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
            MessageRef milestoneUnlocked(@Arg("key") String key);

            @Name("Player offline")
            MessageRef playerOffline();

            @Name("Target unlinked")
            MessageRef targetUnlinked(@Arg("player") PlayerContext player);

            @Name("Objective completed")
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
            MessageRef milestone(@Arg("milestone") MilestoneContext milestone, @Arg("percent") double percent);

            @Name("Distance")
            MessageRef distance(@Arg("blocks") long blocks);

            @Name("Navigate other world")
            MessageRef navigateOtherWorld();
        }

        Navigate navigate();

        @Name("Navigate")
        @Shown(Display.GUI)
        interface Navigate {

            @Name("Title")
            MessageRef title();

            @Name("Stop")
            MessageRef stop();

            @Name("Stopped")
            @Shown(Display.CHAT)
            MessageRef stopped();

            @Name("Started")
            @Shown(Display.CHAT)
            MessageRef started(@Arg("target") String target);

            @Name("World spawn")
            MessageRef worldSpawn();

            @Name("Last death")
            MessageRef lastDeath();

            @Name("At")
            MessageRef at(@Arg("world") String world, @Arg("x") long x, @Arg("y") long y, @Arg("z") long z);

            @Name("Target")
            MessageRef target(@Arg("target") String target);

            @Name("Click")
            MessageRef click();

            @Name("Stop hint")
            MessageRef stopHint();

            @Name("Previous page")
            MessageRef previousPage();

            @Name("Next page")
            MessageRef nextPage();

            @Name("Stop button")
            MessageRef stopButton();

            @Name("Distance")
            MessageRef distance(@Arg("blocks") long blocks);

            @Name("Other world")
            MessageRef otherWorld();

            @Name("Page")
            MessageRef page(@Arg("page") long page, @Arg("pages") long pages);
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
        @Shown(Display.SIDEBAR)
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

        /** Returns a milestone's shipped name, or empty for one only the {@code milestones} group knows. */
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

            @Name("Title")
            MessageRef title();

            @Name("Experience")
            MessageRef experience(@Arg("experience") long experience);

            @Name("Owner")
            MessageRef owner(@Arg("player") PlayerContext player);

            @Name("Owner unknown")
            MessageRef ownerUnknown();

            @Name("Died at")
            MessageRef diedAt(@Arg("at") Instant at, @Arg("x") int x, @Arg("y") int y, @Arg("z") int z);

            @Name("Experience tooltip")
            MessageRef experienceTooltip();

            @Name("Experience hint")
            MessageRef experienceHint(@Arg("experience") long experience);

            @Name("Take all")
            MessageRef takeAll();

            @Name("Take all hint")
            MessageRef takeAllHint();

            @Name("Hologram")
            @Shown(Display.HOLOGRAM)
            MessageRef hologram(@Arg("left") Duration left);

            @Name("Experience line")
            MessageRef experienceLine(@Arg("experience") long experience);

            @Name("Take all button")
            MessageRef takeAllButton();
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

            @Name("Title")
            MessageRef title();

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

            @Name("Hub")
            MessageRef hub(@Arg("spins") long spins);

            @Name("Hub hint")
            MessageRef hubHint(@Arg("percent") double percent);

            @Name("Again")
            MessageRef again();

            @Name("Again hint")
            MessageRef againHint();

            @Name("Again waiting")
            MessageRef againWaiting();

            @Name("Again waiting hint")
            MessageRef againWaitingHint();

            @Name("Again none")
            MessageRef againNone();

            @Name("Again none hint")
            MessageRef againNoneHint();

            @Name("Spins left")
            MessageRef spinsLeft(@Arg("spins") long spins);

            @Name("Rule top")
            MessageRef ruleTop();

            @Name("Rule bottom")
            MessageRef ruleBottom(@Arg("percent") double percent);

            @Name("Again button")
            MessageRef againButton();

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

            @Name("Title")
            MessageRef title();

            @Name("None")
            MessageRef none();

            @Name("Done")
            MessageRef done();

            @Name("Click to hand in")
            MessageRef clickToHandIn();

            @Name("Counts itself")
            MessageRef countsItself();

            @Name("Item")
            MessageRef item(@Arg("objective") String objective);

            @Name("Item done")
            MessageRef itemDone(@Arg("objective") String objective);

            @Name("Progress")
            MessageRef progress(@Arg("bar") String bar, @Arg("amount") long amount, @Arg("target") long target);

            @Name("Items")
            MessageRef items(@Arg("items") List<GameContent> items);

            @Name("Heading")
            MessageRef heading(@Arg("milestone") MilestoneContext milestone);

            @Name("Heading hint")
            MessageRef headingHint(@Arg("done") long done, @Arg("total") long total);

            @Name("Your share")
            MessageRef yourShare(@Arg("percent") double percent, @Arg("spins") long spins);

            @Name("Share tooltip")
            MessageRef shareTooltip();

            @Name("Share line")
            MessageRef shareLine(@Arg("objective") String objective, @Arg("percent") double percent);

            @Name("Share spins")
            MessageRef shareSpins(@Arg("spins") long spins);

            @Name("Share empty hint")
            MessageRef shareEmptyHint();

            @Name("Share")
            MessageRef share(@Arg("spins") long spins);

            @Name("Share none")
            MessageRef shareNone();

            @Name("Previous page")
            MessageRef previousPage();

            @Name("Next page")
            MessageRef nextPage();
        }

        Handin handin();

        @Name("Handin")
        @Shown(Display.GUI)
        interface Handin {

            @Name("Title")
            MessageRef title();

            @Name("Wanted")
            MessageRef wanted();

            @Name("Confirm")
            MessageRef confirm();

            @Name("Needed")
            MessageRef needed(@Arg("amount") long amount, @Arg("items") List<GameContent> items);

            @Name("Still needed")
            MessageRef stillNeeded(@Arg("amount") long amount);

            @Name("Confirm button")
            MessageRef confirmButton();

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

    Tab tab();

    @Name("Tab")
    @Shown(Display.TAB_LIST)
    interface Tab {

        @Name("Footer")
        MessageRef footer(@Arg("online") int online, @Arg("max") int max);
    }
}
