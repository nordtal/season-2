package eu.nordtal.s2.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static eu.nordtal.s2.architecture.Wiring.isListed;
import static eu.nordtal.s2.architecture.Wiring.isOrIsNestedIn;
import static eu.nordtal.s2.architecture.Wiring.reaches;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** What a player reads goes through the message system, and the few exceptions say why. */
class PlayerTextRulesTest {

    /** Every package that talks to a Minecraft client: the Paper plugins and the proxy. */
    static final String[] PLAYER_CODE = {
        "eu.nordtal.s2.papercommon..",
        "eu.nordtal.s2.smp..",
        "eu.nordtal.s2.hungergames..",
        "eu.nordtal.s2.limbo..",
        "eu.nordtal.s2.proxy.."
    };

    private static final String COMPONENT = "net.kyori.adventure.text.Component";
    private static final String MESSAGES = "eu.nordtal.s2.messages.Messages";
    private static final String MESSAGE_REF = "eu.nordtal.s2.messages.MessageRef";
    private static final String TONE = "eu.nordtal.s2.messages.Tone";
    private static final String PLUGIN_BASE = "eu.nordtal.s2.papercommon.plugin.NordtalPlugin";
    private static final String SYSTEM_LINES = "eu.nordtal.s2.papercommon.chat.SystemLines";
    private static final String BOSS_BAR = "net.kyori.adventure.bossbar.BossBar";
    private static final String BOSS_BAR_LINE = "eu.nordtal.s2.packrendering.hud.BossBarLine";
    private static final String BOSS_BAR_WIDTH = "eu.nordtal.s2.packrendering.hud.BossBarWidth";
    private static final String GLYPHS = "eu.nordtal.s2.packrendering.Glyphs";
    private static final String MENU_TITLE = "eu.nordtal.s2.papercommon.menu.MenuTitle";
    private static final String MENU = "eu.nordtal.s2.papercommon.menu.Menu";

    /** The classes that compose a component by hand, and why that is not a message going around the renderer. */
    private static final Map<String, String> COMPOSE_BY_HAND = Map.ofEntries(
            Map.entry("eu.nordtal.s2.smp.player.PlayerComposition", "glyphs and player names: nametag, tab, chat"),
            Map.entry(
                    "eu.nordtal.s2.papercommon.game.GameDataExport",
                    "placeholders in a game name rendered for Steward, never shown to a player"),
            Map.entry("eu.nordtal.s2.smp.board.BoardFrame", "pack glyphs around lines already rendered"),
            Map.entry(
                    "eu.nordtal.s2.papercommon.menu.MenuTitle",
                    "pack glyphs and spacing around a title already rendered"),
            Map.entry("eu.nordtal.s2.smp.npc.SpawnNpc", "the NPC's name from config.yml, a name and not a message"),
            Map.entry("eu.nordtal.s2.smp.welcome.SeasonWelcome", "the opening frames, pictures and not sentences"),
            Map.entry("eu.nordtal.s2.hungergames.body.PlayerBodies", "a disconnected player's name on their body"),
            Map.entry("eu.nordtal.s2.hungergames.player.ArenaComposition", "a flag glyph and a player name"),
            Map.entry("eu.nordtal.s2.proxy.command.VelocityUser", "replyLiteral, text that is already the answer"),
            Map.entry("eu.nordtal.s2.papercommon.command.PaperUser", "replyLiteral, text that is already the answer"));

    /** The two users that offer a reply without a tone, which every other caller is measured against. */
    private static final String[] UNTONED_REPLIES = {
        "eu.nordtal.s2.papercommon.command.PaperUser", "eu.nordtal.s2.proxy.command.VelocityUser"
    };

    /** The two classes that answer a command somebody may not, or cannot, run. */
    private static final String[] REFUSERS = {
        "eu.nordtal.s2.papercommon.command.CommandFilter", "eu.nordtal.s2.proxy.command.CommandGate"
    };

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = Codebase.classes();
    }

    @Test
    void onlyTheListedClassesComposeAComponentByHand() {
        noClasses()
                .that()
                .resideInAnyPackage(PLAYER_CODE)
                .and(DescribedPredicate.not(
                        isOrIsNestedIn(COMPOSE_BY_HAND.keySet().toArray(String[]::new))))
                .should()
                .accessTargetWhere(reaches(COMPONENT, "text"))
                .because("a message composed by hand skips MessageRenderer, which knows the format and escapes"
                        + " what is substituted; text that is not a message is listed with its reason")
                .check(classes);
    }

    @Test
    void everyListedClassStillComposesByHand() {
        classes()
                .that(isListed(COMPOSE_BY_HAND.keySet().toArray(String[]::new)))
                .should(Wiring.reachInside(reaches(COMPONENT, "text")))
                .because("an exception nobody takes any more is a hole for the next class of that name")
                .check(classes);
    }

    @Test
    void noMessageTemplateIsWrappedAsPlainText() {
        classes()
                .that()
                .resideInAnyPackage(PLAYER_CODE)
                .should(Wiring.neverOnOneLine(reaches(COMPONENT, "text"), templateRead()))
                .because("Component.text(messages.format(...)) prints a MiniMessage tag as the literal text '<red>'")
                .check(classes);
    }

    @Test
    void everyDiscordTextGoesThroughTheDiscordTarget() {
        noClasses()
                .that()
                .resideInAPackage("eu.nordtal.s2.discordbot..")
                .should()
                .callMethodWhere(DescribedPredicate.describe(
                        "Messages#format",
                        call -> call.getTargetOwner().getName().equals(MESSAGES)
                                && call.getName().equals("format")))
                .because("the plain target escapes nothing, so a team name with _ turns the rest of a Discord line"
                        + " italic; DiscordRenderer escapes each value and writes times and members as Discord's own")
                .check(classes);
    }

    @Test
    void everyReplyNamesATone() {
        noClasses()
                .that()
                .resideInAnyPackage(PLAYER_CODE)
                .and(DescribedPredicate.not(isOrIsNestedIn(UNTONED_REPLIES)))
                .should()
                .callMethodWhere(DescribedPredicate.describe(
                        "a reply of a message without a Tone",
                        call -> call.getName().equals("reply")
                                && takes(call.getTarget().getRawParameterTypes(), MESSAGE_REF)
                                && !takes(call.getTarget().getRawParameterTypes(), TONE)))
                .because("a reply with no tone is drawn in whatever colour the client was already using")
                .check(classes);
    }

    @Test
    void bothRefusersSayOnlyTheOneUnknownCommandLine() {
        classes()
                .that(isListed(REFUSERS))
                .should(renderOnlyTheUnknownCommandLine())
                .because("a second sentence tells a player whether they hit 'you may not' or 'there is no such"
                        + " command', which is what the allowlist keeps from them")
                .check(classes);
    }

    @Test
    void thePaperSideAnswersAnUnknownCommandItself() {
        methods()
                .that()
                .areDeclaredIn(REFUSERS[0])
                .and()
                .haveRawParameterTypes("org.bukkit.event.command.UnknownCommandEvent")
                .should()
                .beAnnotatedWith("org.bukkit.event.EventHandler")
                .because("without it a typo reads vanilla's 'Unknown or incomplete command', in the server's"
                        + " language")
                .check(classes);
    }

    @Test
    void onlyThePluginBaseBuildsTheSystemLines() {
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName(PLUGIN_BASE)
                .should()
                .callConstructorWhere(DescribedPredicate.describe(
                        "new SystemLines",
                        call -> call.getTargetOwner().getName().equals(SYSTEM_LINES)))
                .because("the base registers what it builds, and lines built and never registered look the same"
                        + " as lines that work")
                .check(classes);
    }

    @Test
    void bothServersWherePlayersSeeEachOtherHaveTheSystemLines() {
        for (final String server : new String[] {"eu.nordtal.s2.smp.", "eu.nordtal.s2.hungergames."}) {
            assertTrue(
                    classes.stream()
                            .filter(type -> type.getName().startsWith(server))
                            .flatMap(type -> type.getAccessesFromSelf().stream())
                            .anyMatch(reaches(PLUGIN_BASE, "systemLines")),
                    "no class in " + server + " asks the base for the system lines, so chat, join, leave, death"
                            + " and advancement there are vanilla's: one language, no flag, and yellow");
        }
    }

    /** A boss bar name not built by BossBarLine resolves its glyphs against {@code minecraft:default}. */
    @Test
    void onlyBossBarLineNamesABossBar() {
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName(BOSS_BAR_LINE)
                .should()
                .callMethodWhere(DescribedPredicate.describe(
                        "BossBar.name(...) or BossBar.bossBar(...)", PlayerTextRulesTest::namesABossBar))
                .because("BossBarLine is the one place the bossbar font is named and the shadow turned off")
                .check(classes);
    }

    /** The pill background is BossBarLine's; a second composition of it is the one that drifts. */
    @Test
    void nothingOutsideThePackRenderingComposesAPillBackground() {
        noClasses()
                .that()
                .resideOutsideOfPackage("eu.nordtal.s2.packrendering..")
                .should()
                .accessTargetWhere(DescribedPredicate.describe(
                        "a boss bar background glyph or BossBarWidth", PlayerTextRulesTest::isPillBackground))
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName(BOSS_BAR_WIDTH)
                .check(classes);
    }

    /** A chest window's height is even for every row count, which the panel arithmetic needs; a hopper's is not. */
    @Test
    void noMenuOpensAnythingButAChest() {
        noClasses()
                .that()
                .resideInAnyPackage(PLAYER_CODE)
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.bukkit.event.inventory.InventoryType")
                .check(classes);
    }

    /** A menu whose title skips MenuTitle opens as a plain vanilla window, and nothing about that fails. */
    @Test
    void everyMenuTakesItsTitleFromMenuTitle() {
        classes()
                .that()
                .resideInAnyPackage(PLAYER_CODE)
                .should(openOnlyFramedMenus())
                .check(classes);
        assertTrue(
                classes.get("eu.nordtal.s2.smp.navigate.NavigateGui").getMethodCallsFromSelf().stream()
                        .anyMatch(PlayerTextRulesTest::framesAMenu),
                "NavigateGui, the reference menu, frames no window, so this rule may be checking nothing");
    }

    private static boolean namesABossBar(final JavaMethodCall call) {
        if (!call.getTargetOwner().getName().equals(BOSS_BAR)) {
            return false;
        }
        final boolean setsTheName = call.getName().equals("name")
                && !call.getTarget().getRawParameterTypes().isEmpty();
        return setsTheName || call.getName().equals("bossBar");
    }

    private static boolean isPillBackground(final JavaAccess<?> access) {
        final String owner = access.getTargetOwner().getName();
        final boolean tile =
                owner.equals(GLYPHS) && access.getTarget().getName().startsWith("BOSSBAR_BG_");
        return tile || owner.equals(BOSS_BAR_WIDTH);
    }

    private static boolean takes(final List<JavaClass> parameters, final String type) {
        return parameters.stream().anyMatch(parameter -> parameter.getName().equals(type));
    }

    /** A read of a message template as a string, which only MessageRenderer may turn into a component. */
    private static DescribedPredicate<JavaAccess<?>> templateRead() {
        return DescribedPredicate.describe(
                "a message template as text",
                access -> access.getTargetOwner().getName().equals(MESSAGES)
                        && (access.getTarget().getName().equals("get")
                                || access.getTarget().getName().equals("format")));
    }

    /** Every message key a class renders is {@code command.unknown}: {@code unknown()} on the spec's command(). */
    private static ArchCondition<JavaClass> renderOnlyTheUnknownCommandLine() {
        return new ArchCondition<>("render no message but command.unknown") {
            @Override
            public void check(final JavaClass type, final ConditionEvents events) {
                boolean rendersIt = false;
                for (final JavaClass each : Wiring.withNested(type)) {
                    for (final JavaCodeUnit unit : each.getCodeUnits()) {
                        for (final var call : unit.getMethodCallsFromSelf()) {
                            final Optional<JavaMethod> target = call.getTarget().resolveMember();
                            if (target.isEmpty()
                                    || !target.get()
                                            .getRawReturnType()
                                            .getName()
                                            .equals(MESSAGE_REF)) {
                                continue;
                            }
                            if (isUnknownCommand(target.get())) {
                                rendersIt = true;
                            } else {
                                events.add(SimpleConditionEvent.violated(
                                        type,
                                        type.getName() + " renders "
                                                + target.get().getFullName()));
                            }
                        }
                    }
                }
                if (!rendersIt) {
                    events.add(SimpleConditionEvent.violated(type, type.getName() + " renders no command.unknown"));
                }
            }
        };
    }

    private static boolean isUnknownCommand(final JavaMethod message) {
        final JavaClass section = message.getOwner();
        return message.getName().equals("unknown")
                && section.getEnclosingClass()
                        .map(spec -> spec.getMethods().stream()
                                .anyMatch(method -> method.getName().equals("command")
                                        && method.getRawReturnType().equals(section)))
                        .orElse(false);
    }

    /** A call of {@code Menu#frame}, the one place a window is made. */
    private static boolean framesAMenu(final JavaMethodCall call) {
        return call.getName().equals("frame")
                && call.getTarget()
                        .resolveMember()
                        .map(method -> method.getOwner().getName().equals(MENU))
                        .orElse(false);
    }

    /**
     * Every code unit that frames a menu also takes a component from MenuTitle, or from a panel built on it.
     */
    private static ArchCondition<JavaClass> openOnlyFramedMenus() {
        return new ArchCondition<>("open no inventory without a MenuTitle") {
            @Override
            public void check(final JavaClass type, final ConditionEvents events) {
                for (final JavaCodeUnit unit : type.getCodeUnits()) {
                    final boolean opens =
                            unit.getMethodCallsFromSelf().stream().anyMatch(PlayerTextRulesTest::framesAMenu);
                    final boolean framed = unit.getMethodCallsFromSelf().stream()
                            .anyMatch(call -> call.getTarget()
                                            .getRawReturnType()
                                            .getName()
                                            .equals(COMPONENT)
                                    && (call.getTargetOwner().getName().equals(MENU_TITLE)
                                            || call.getTargetOwner().getAccessesFromSelf().stream()
                                                    .anyMatch(access -> access.getTargetOwner()
                                                            .getName()
                                                            .equals(MENU_TITLE))));
                    if (opens && !framed) {
                        events.add(SimpleConditionEvent.violated(
                                unit, unit.getFullName() + " opens an inventory whose title is not MenuTitle's"));
                    }
                }
            }
        };
    }
}
