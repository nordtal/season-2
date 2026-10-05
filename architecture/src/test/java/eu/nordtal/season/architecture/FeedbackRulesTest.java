package eu.nordtal.season.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static eu.nordtal.season.architecture.PlayerTextRulesTest.PLAYER_CODE;
import static eu.nordtal.season.architecture.Wiring.isListed;
import static eu.nordtal.season.architecture.Wiring.isOrIsNestedIn;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A call site picks a feedback category or a moment; only one adapter per module names a sound or an effect. */
class FeedbackRulesTest {

    /** The sound adapters, one per module that plays sounds: each turns a category into a packet. */
    private static final String[] SOUND_ADAPTERS = {
        "eu.nordtal.season.smp.feedback.SmpSounds",
        "eu.nordtal.season.hungergames.feedback.HungerGamesSounds",
        "eu.nordtal.season.proxy.feedback.ProxySounds"
    };

    /** The one effect adapter, smp's: the only place that draws anything in a world. */
    private static final String WORLD_EFFECTS = "eu.nordtal.season.smp.feedback.WorldEffects";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = Codebase.classes();
    }

    @Test
    void onlyTheSoundAdaptersNameASound() {
        noClasses()
                .that()
                .resideInAnyPackage(PLAYER_CODE)
                .and(DescribedPredicate.not(isOrIsNestedIn(SOUND_ADAPTERS)))
                .should()
                .dependOnClassesThat(DescribedPredicate.describe(
                        "a platform sound",
                        (final JavaClass type) -> type.getName().startsWith("org.bukkit.Sound")
                                || type.getPackageName().startsWith("net.kyori.adventure.sound")))
                .orShould()
                .callMethodWhere(DescribedPredicate.describe(
                        "playSound", call -> call.getName().equals("playSound")))
                .because("the sound key comes from config.yml; a call site chooses a Feedback category")
                .check(classes);
    }

    @Test
    void everySoundAdapterStillPlaysSomething() {
        classes()
                .that(isListed(SOUND_ADAPTERS))
                .should(Wiring.reachInside(named("playSound")))
                .because("an adapter that plays nothing is an exception nobody takes any more")
                .check(classes);
    }

    @Test
    void onlyTheEffectAdapterNamesAParticleOrAFirework() {
        noClasses()
                .that()
                .resideInAnyPackage(PLAYER_CODE)
                .and(DescribedPredicate.not(isOrIsNestedIn(WORLD_EFFECTS)))
                .should()
                .dependOnClassesThat(DescribedPredicate.describe(
                        "a particle or a firework",
                        (final JavaClass type) -> type.getName().startsWith("org.bukkit.Particle")
                                || type.getName().equals("org.bukkit.entity.Firework")))
                .orShould()
                .callMethodWhere(DescribedPredicate.describe(
                        "spawnParticle", call -> call.getName().equals("spawnParticle")))
                .because("a moment that picks its own look drifts, and a rocket the adapter did not stamp still"
                        + " damages whatever is near it")
                .check(classes);
    }

    @Test
    void theEffectAdapterStillDrawsSomething() {
        classes()
                .that(isListed(WORLD_EFFECTS))
                .should(Wiring.reachInside(named("spawnParticle")))
                .check(classes);
    }

    @Test
    void everyRocketTheAdapterLaunchesIsStamped() {
        classes().that(isListed(WORLD_EFFECTS)).should(stampEveryRocket()).check(classes);
    }

    @Test
    void theAdapterRefusesTheDamageOfItsOwnRockets() {
        methods()
                .that()
                .areDeclaredIn(WORLD_EFFECTS)
                .and()
                .haveRawParameterTypes("org.bukkit.event.entity.EntityDamageByEntityEvent")
                .should(cancelTheEvent())
                .because("without that handler the stamp is decoration")
                .check(classes);
    }

    private static DescribedPredicate<JavaAccess<?>> named(final String member) {
        return DescribedPredicate.describe(
                member, access -> access.getTarget().getName().equals(member));
    }

    /** Every code unit that spawns a {@code Firework} also writes the stamp {@code onDamage} reads. */
    private static ArchCondition<JavaClass> stampEveryRocket() {
        return new ArchCondition<>("stamp every rocket it spawns") {
            @Override
            public void check(final JavaClass type, final ConditionEvents events) {
                int launches = 0;
                for (final JavaCodeUnit unit : type.getCodeUnits()) {
                    final boolean spawnsOne = unit.getReferencedClassObjects().stream()
                            .anyMatch(object -> object.getRawType().getName().equals("org.bukkit.entity.Firework"));
                    if (!spawnsOne) {
                        continue;
                    }
                    launches++;
                    final boolean stamps = unit.getMethodCallsFromSelf().stream()
                            .anyMatch(call -> call.getName().equals("set")
                                    && call.getTargetOwner()
                                            .getName()
                                            .equals("org.bukkit.persistence.PersistentDataContainer"));
                    if (!stamps) {
                        events.add(SimpleConditionEvent.violated(
                                unit, unit.getFullName() + " spawns a Firework and never stamps it"));
                    }
                }
                if (launches == 0) {
                    events.add(SimpleConditionEvent.violated(type, type.getName() + " launches no rocket any more"));
                }
            }
        };
    }

    private static ArchCondition<JavaMethod> cancelTheEvent() {
        return new ArchCondition<>("cancel the event") {
            @Override
            public void check(final JavaMethod method, final ConditionEvents events) {
                if (method.getMethodCallsFromSelf().stream()
                        .noneMatch(call -> call.getName().equals("setCancelled"))) {
                    events.add(SimpleConditionEvent.violated(method, method.getFullName() + " cancels nothing"));
                }
            }
        };
    }
}
