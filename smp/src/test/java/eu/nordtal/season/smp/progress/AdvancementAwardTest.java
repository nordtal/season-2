package eu.nordtal.season.smp.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.smp.config.AdvancementAwardSpec;
import eu.nordtal.season.smp.progress.AdvancementListener.Award;
import eu.nordtal.season.spec.Specs;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdvancementAwardTest {

    private static final List<AdvancementAwardSpec> AWARDS = List.of(
            award("minecraft:end/kill_dragon", 10, 2),
            award("minecraft:story/mine_stone", 2, 0),
            award("minecraft:Story/Mine_Diamond", 4, 1));

    @Test
    void anAwardPaysItsAuraAndItsSpins() {
        assertEquals(Optional.of(new Award(10, 2)), AdvancementListener.awardOf(AWARDS, "minecraft:end/kill_dragon"));
    }

    @Test
    void anAwardWithoutSpinsPaysOnlyAura() {
        assertEquals(Optional.of(new Award(2, 0)), AdvancementListener.awardOf(AWARDS, "minecraft:story/mine_stone"));
    }

    @Test
    void anAdvancementIsMatchedWithoutCase() {
        assertEquals(Optional.of(new Award(4, 1)), AdvancementListener.awardOf(AWARDS, "minecraft:story/mine_diamond"));
    }

    @Test
    void anAdvancementWithoutAnAwardPaysNothing() {
        assertEquals(Optional.empty(), AdvancementListener.awardOf(AWARDS, "minecraft:story/smelt_iron"));
    }

    private static AdvancementAwardSpec award(final String advancement, final int aura, final int spins) {
        return Specs.createUnsafe(
                AdvancementAwardSpec.class, Map.of("advancement", advancement, "aura", aura, "spins", spins));
    }
}
