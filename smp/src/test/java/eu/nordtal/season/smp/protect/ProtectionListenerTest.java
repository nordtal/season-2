package eu.nordtal.season.smp.protect;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.NPC;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.Test;

class ProtectionListenerTest {

    @Test
    void protectedEntitiesIncludePlayersAnimalsNpcsMannequinsArmorStandsAndItemFrames() {
        assertTrue(ProtectionListener.isProtectedFromDamage(stub(Player.class)));
        assertTrue(ProtectionListener.isProtectedFromDamage(stub(Animals.class)));
        assertTrue(ProtectionListener.isProtectedFromDamage(stub(NPC.class)));
        assertTrue(ProtectionListener.isProtectedFromDamage(stub(Mannequin.class)));
        assertTrue(ProtectionListener.isProtectedFromDamage(stub(ArmorStand.class)));
        assertTrue(ProtectionListener.isProtectedFromDamage(stub(ItemFrame.class)));
        assertFalse(ProtectionListener.isProtectedFromDamage(stub(Zombie.class)));
    }

    @Test
    void damageIsBlockedInSpawnExceptForActiveDuelFightersAndCreativeAdminDecoratives() {
        // Inside spawn, protected entity, no exemption -> blocked.
        assertTrue(ProtectionListener.blocksDamage(true, true, false, false));
        // Outside spawn -> allowed.
        assertFalse(ProtectionListener.blocksDamage(false, true, false, false));
        // Unprotected entity (hostile mob) -> allowed.
        assertFalse(ProtectionListener.blocksDamage(true, false, false, false));
        // Active fighter inside duel arena -> exempt!
        assertFalse(ProtectionListener.blocksDamage(true, true, true, false));
        // Creative admin breaking armor stand or item frame -> allowed.
        assertFalse(ProtectionListener.blocksDamage(true, true, false, true));
    }

    @Test
    void mobGriefingIsBlockedOnlyInsideSpawn() {
        assertTrue(ProtectionListener.blocksMobGrief(true, true));
        assertFalse(ProtectionListener.blocksMobGrief(false, true));
        assertFalse(ProtectionListener.blocksMobGrief(true, false));
    }

    private static <T extends Entity> T stub(final Class<T> type) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (_, method, _) -> switch (method.getName()) {
                    case "toString" -> type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(type);
                    case "equals" -> false;
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
    }
}
