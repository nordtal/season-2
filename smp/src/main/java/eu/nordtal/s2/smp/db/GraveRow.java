package eu.nordtal.s2.smp.db;

import java.util.UUID;

/**
 * One grave as it is stored.
 *
 * <p>{@code contents} is {@code ItemStack.serializeItemsAsBytes} - NBT with the server's own data
 * fixers behind it, which is the format that survives a Minecraft update. Bukkit's
 * {@code ConfigurationSerializable} map was rejected because it loses data components that have no
 * map representation, and a hand-rolled format because it would have to be taught every new item
 * component by hand. Nothing ever queries <em>into</em> a grave; it is written once and read back
 * whole, so there is nothing traded away.
 *
 * <p>{@code ownerUuid} is joined in from {@code account_link} rather than stored: the schema is keyed
 * by Discord account throughout, and the Minecraft UUID is only wanted here so the head on top of
 * the grave is the right person's face.
 */
public record GraveRow(UUID id, String ownerId, UUID ownerUuid, String world, int x, int y, int z,
                       byte[] contents, int experience) {
}
