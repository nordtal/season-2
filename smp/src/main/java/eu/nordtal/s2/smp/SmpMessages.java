package eu.nordtal.s2.smp;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.context.MilestoneContext;
import eu.nordtal.s2.common.message.context.PlayerContext;
import eu.nordtal.s2.common.message.spec.Arg;
import eu.nordtal.s2.common.message.spec.Display;
import eu.nordtal.s2.common.message.spec.Format;
import eu.nordtal.s2.common.message.spec.Key;
import eu.nordtal.s2.common.message.spec.MessageSpec;
import eu.nordtal.s2.common.message.spec.MessageSpecs;
import eu.nordtal.s2.common.message.spec.Name;
import eu.nordtal.s2.common.message.spec.Shown;
import eu.nordtal.s2.common.message.spec.TextFormat;
import eu.nordtal.s2.smp.world.WorldRole;
import java.util.Optional;

/**
 * Every message of the smp bundle, one method per key.
 */
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
            MessageRef card(@Arg("world") Object world);

            @Name("Card locked")
            MessageRef cardLocked(@Arg("world") Object world);

            @Name("Locked unknown")
            MessageRef lockedUnknown();

            @Name("Travelled")
            @Shown(Display.CHAT)
            MessageRef travelled(@Arg("world") Object world);

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

        Error error();

        @Name("Error")
        interface Error {

            @Name("Database unreachable")
            MessageRef databaseUnreachable();

            @Name("No account link")
            MessageRef noAccountLink();
        }

        Hud hud();

        @Name("HUD")
        @Shown(Display.BOSS_BAR)
        interface Hud {

            @Name("Milestone")
            MessageRef milestone(@Arg("milestone") MilestoneContext milestone, @Arg("percent") Object percent);

            @Name("Distance")
            MessageRef distance(@Arg("blocks") Object blocks);

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
            MessageRef started(@Arg("target") Object target);

            @Name("World spawn")
            MessageRef worldSpawn();

            @Name("Last death")
            MessageRef lastDeath();

            @Name("At")
            MessageRef at(@Arg("world") Object world, @Arg("x") Object x, @Arg("y") Object y, @Arg("z") Object z);

            @Name("Target")
            MessageRef target(@Arg("target") Object target);

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
            MessageRef distance(@Arg("blocks") Object blocks);

            @Name("Other world")
            MessageRef otherWorld();

            @Name("Page")
            MessageRef page(@Arg("page") Object page, @Arg("pages") Object pages);
        }

        Poi poi();

        @Name("Places")
        interface Poi {

            @Name("Added")
            MessageRef added(@Arg("name") Object name);

            @Name("Removed")
            MessageRef removed(@Arg("name") Object name);

            @Name("Duplicate")
            MessageRef duplicate(@Arg("name") Object name);

            @Name("Not found")
            MessageRef notFound(@Arg("name") Object name);

            @Name("Not yours")
            MessageRef notYours();

            @Name("Bad name")
            MessageRef badName(@Arg("max") Object max);
        }

        Board board();

        @Name("Board")
        @Shown(Display.SIDEBAR)
        interface Board {

            Objective objective();

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
                        @Arg("objective") Object objective,
                        @Arg("bar") Object bar,
                        @Arg("amount") Object amount,
                        @Arg("target") Object target);

                @Name("Row done")
                MessageRef rowDone(
                        @Arg("objective") Object objective,
                        @Arg("bar") Object bar,
                        @Arg("amount") Object amount,
                        @Arg("target") Object target);
            }

            Aura aura();

            @Name("Aura")
            interface Aura {

                @Name("Title")
                MessageRef title();

                @Name("Empty")
                MessageRef empty();

                @Name("Row")
                MessageRef row(
                        @Arg("place") Object place, @Arg("player") PlayerContext player, @Arg("aura") Object aura);

                @Name("Row zero")
                MessageRef rowZero(
                        @Arg("place") Object place, @Arg("player") PlayerContext player, @Arg("aura") Object aura);
            }
        }

        Aura aura();

        @Name("Aura")
        interface Aura {

            @Name("Death")
            MessageRef death(@Arg("aura") Object aura);

            @Name("Advancement")
            MessageRef advancement(@Arg("aura") Object aura);

            @Name("Own")
            MessageRef own(@Arg("aura") Object aura, @Arg("rank") Object rank, @Arg("total") Object total);

            @Name("Top")
            MessageRef top(@Arg("count") Object count);

            @Name("Line")
            MessageRef line(@Arg("place") Object place, @Arg("player") PlayerContext player, @Arg("aura") Object aura);

            @Name("Empty")
            MessageRef empty();

            @Name("Unlinked")
            MessageRef unlinked();

            @Name("Failed")
            MessageRef failed();
        }

        Status status();

        @Name("Status")
        interface Status {

            @Name("Milestone")
            MessageRef milestone(@Arg("milestone") MilestoneContext milestone, @Arg("percent") Object percent);

            @Name("Finished")
            MessageRef finished();

            @Name("Unread")
            MessageRef unread();

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

        Headstart headstart();

        @Name("Headstart")
        interface Headstart {

            @Name("Granted")
            MessageRef granted(@Arg("aura") Object aura);

            @Name("Granted aura only")
            MessageRef grantedAuraOnly(@Arg("aura") Object aura);
        }

        Objective objective();

        @Name("Objective")
        interface Objective {

            @Name("Completed")
            MessageRef completed(@Arg("icon") Object icon, @Arg("objective") Object objective);
        }

        Milestone milestone();

        /**
         * A milestone's shipped name, or empty for one only {@code milestones.yml} knows - which then
         * shows under its config key.
         */
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
            MessageRef completed(@Arg("icon") Object icon, @Arg("milestone") MilestoneContext milestone);
        }

        Announce announce();

        @Name("Announce")
        @Shown(Display.DISCORD_MESSAGE)
        @Format(TextFormat.DISCORD_MARKDOWN)
        interface Announce {

            @Name("Milestone")
            MessageRef milestone(@Arg("milestone") MilestoneContext milestone);

            @Key("milestone")
            Milestone milestoneSection();

            @Name("Milestone")
            interface Milestone {

                @Name("Border")
                MessageRef border(@Arg("milestone") MilestoneContext milestone);

                @Name("Nether")
                MessageRef nether(@Arg("milestone") MilestoneContext milestone);

                @Name("End")
                MessageRef end(@Arg("milestone") MilestoneContext milestone);
            }
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
            MessageRef experience(@Arg("experience") Object experience);

            @Name("Owner")
            MessageRef owner(@Arg("player") PlayerContext player);

            @Name("Owner unknown")
            MessageRef ownerUnknown();

            @Name("Died at")
            MessageRef diedAt(@Arg("date") Object date, @Arg("x") Object x, @Arg("y") Object y, @Arg("z") Object z);

            @Name("Experience tooltip")
            MessageRef experienceTooltip();

            @Name("Experience hint")
            MessageRef experienceHint(@Arg("experience") Object experience);

            @Name("Take all")
            MessageRef takeAll();

            @Name("Take all hint")
            MessageRef takeAllHint();

            @Name("Hologram")
            @Shown(Display.HOLOGRAM)
            MessageRef hologram(@Arg("hours") Object hours, @Arg("minutes") Object minutes);

            @Name("Hologram seconds")
            @Shown(Display.HOLOGRAM)
            MessageRef hologramSeconds(@Arg("seconds") Object seconds);

            @Name("Experience line")
            MessageRef experienceLine(@Arg("experience") Object experience);

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
            MessageRef countdown(@Arg("seconds") Object seconds);

            @Name("Go")
            MessageRef go();

            @Name("Won")
            MessageRef won(@Arg("aura") Object aura);

            @Name("Lost")
            MessageRef lost(@Arg("aura") Object aura);

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
                MessageRef subtitle(@Arg("aura") Object aura);
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
                MessageRef subtitle(@Arg("aura") Object aura);
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
            MessageRef won(@Arg("amount") Object amount, @Arg("item") Object item);

            @Name("None")
            @Shown(Display.CHAT)
            MessageRef none();

            @Name("Available")
            MessageRef available();

            @Name("Broken prize")
            @Shown(Display.CHAT)
            MessageRef brokenPrize();

            @Name("Hub")
            MessageRef hub(@Arg("spins") Object spins);

            @Name("Hub hint")
            MessageRef hubHint(@Arg("percent") Object percent);

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
            MessageRef spinsLeft(@Arg("spins") Object spins);

            @Name("Rule top")
            MessageRef ruleTop();

            @Name("Rule bottom")
            MessageRef ruleBottom(@Arg("percent") Object percent);

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
                MessageRef many(@Arg("extras") Object extras);
            }

            @Key("available")
            Available availableSection();

            @Name("Available")
            @Shown(Display.CHAT)
            interface Available {

                @Name("One")
                MessageRef one();

                @Name("Many")
                MessageRef many(@Arg("count") Object count);
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
            MessageRef item(@Arg("objective") Object objective);

            @Name("Item done")
            MessageRef itemDone(@Arg("objective") Object objective);

            @Name("Progress")
            MessageRef progress(@Arg("bar") Object bar, @Arg("amount") Object amount, @Arg("target") Object target);

            @Name("Items")
            MessageRef items(@Arg("items") Object items);

            @Name("Heading")
            MessageRef heading(@Arg("milestone") MilestoneContext milestone);

            @Name("Heading hint")
            MessageRef headingHint(@Arg("done") Object done, @Arg("total") Object total);

            @Name("Your share")
            MessageRef yourShare(@Arg("percent") Object percent, @Arg("spins") Object spins);

            @Name("Share tooltip")
            MessageRef shareTooltip();

            @Name("Share line")
            MessageRef shareLine(@Arg("objective") Object objective, @Arg("percent") Object percent);

            @Name("Share spins")
            MessageRef shareSpins(@Arg("spins") Object spins);

            @Name("Share empty hint")
            MessageRef shareEmptyHint();

            @Name("Share")
            MessageRef share(@Arg("spins") Object spins);

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
            MessageRef needed(@Arg("amount") Object amount, @Arg("items") Object items);

            @Name("Still needed")
            MessageRef stillNeeded(@Arg("amount") Object amount);

            @Name("Confirm button")
            MessageRef confirmButton();

            @Name("Accepted")
            @Shown(Display.CHAT)
            MessageRef accepted(@Arg("amount") Object amount);

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

            Poi poi();

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

        @Name("Header")
        MessageRef header(@Arg("logo") Object logo);

        @Name("Footer")
        MessageRef footer(@Arg("online") Object online, @Arg("max") Object max);
    }
}
