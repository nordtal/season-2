package eu.nordtal.s2.smp.milestone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;

/**
 * Refuses a track naming an item, statistic, entity or advancement this server does not have.
 *
 * The hand-in and the statistic poller read names through the same lookups, so a name passed here is found there.
 */
public final class TrackNames {

    /** What only a running server can answer about a name. */
    public interface Server {

        boolean isItem(Material material);

        boolean isBlock(Material material);

        boolean hasAdvancement(NamespacedKey key);

        /** Returns the answers of the server this plugin runs on. */
        static Server running() {
            return new Server() {
                @Override
                public boolean isItem(final Material material) {
                    return material.isItem();
                }

                @Override
                public boolean isBlock(final Material material) {
                    return material.isBlock();
                }

                @Override
                public boolean hasAdvancement(final NamespacedKey key) {
                    return Bukkit.getAdvancement(key) != null;
                }
            };
        }
    }

    private TrackNames() {}

    /** Returns every name the track uses that this server does not have, empty when all of them resolve. */
    public static List<TrackValidation.Problem> validate(final MilestoneTrack track, final Server server) {
        final List<TrackValidation.Problem> problems = new ArrayList<>();
        for (final Milestone milestone : track.milestones()) {
            for (final Objective objective : milestone.objectives()) {
                final List<String> unknown = switch (objective.type()) {
                    case HAND_IN -> itemProblems(objective, server);
                    case STATISTIC -> statisticProblems(objective, server);
                    case ADVANCEMENT -> advancementProblems(objective, server);
                };
                unknown.forEach(message ->
                        problems.add(new TrackValidation.Problem(milestone.key(), objective.key(), message)));
            }
        }
        return List.copyOf(problems);
    }

    /** Returns the material a track names by its Bukkit name, in any case; empty for an unknown or legacy one. */
    public static Optional<Material> material(final String name) {
        final Material material = Material.getMaterial(name.trim().toUpperCase(Locale.ROOT));
        return material == null || material.isLegacy() ? Optional.empty() : Optional.of(material);
    }

    /** Returns the statistic a track names by its Bukkit name, in any case. */
    public static Optional<Statistic> statistic(final String name) {
        return Arrays.stream(Statistic.values())
                .filter(statistic -> statistic.name().equalsIgnoreCase(name.trim()))
                .findFirst();
    }

    /** Returns the entity type a track names by its Bukkit name, in any case; never {@code UNKNOWN}. */
    public static Optional<EntityType> entity(final String name) {
        return Arrays.stream(EntityType.values())
                .filter(type -> type != EntityType.UNKNOWN && type.name().equalsIgnoreCase(name.trim()))
                .findFirst();
    }

    private static List<String> itemProblems(final Objective objective, final Server server) {
        final List<String> problems = new ArrayList<>();
        for (final String item : objective.items()) {
            if (material(item).filter(server::isItem).isEmpty()) {
                problems.add("wants '" + item + "', which is no item on this server. Items are Bukkit material"
                        + " names, such as OAK_LOG.");
            }
        }
        return problems;
    }

    private static List<String> statisticProblems(final Objective objective, final Server server) {
        final Statistic statistic = statistic(objective.statistic()).orElse(null);
        if (statistic == null) {
            return List.of("counts '" + objective.statistic() + "', which is no statistic on this server."
                    + " Statistics are Bukkit names, such as MINE_BLOCK.");
        }
        final Statistic.Type type = statistic.getType();
        if (type == Statistic.Type.UNTYPED) {
            return objective.subjects().isEmpty()
                    ? List.of()
                    : List.of("counts " + statistic + ", which has no subjects, yet names " + objective.subjects()
                            + "; every one of them would count it again.");
        }
        if (objective.subjects().isEmpty()) {
            return List.of("counts " + statistic + ", which is kept per " + kind(type)
                    + ", but names none, so it would never move.");
        }
        final List<String> problems = new ArrayList<>();
        for (final String subject : objective.subjects()) {
            if (!isSubject(type, subject, server)) {
                problems.add("counts " + statistic + " over '" + subject + "', which is no " + kind(type)
                        + " on this server.");
            }
        }
        return problems;
    }

    private static boolean isSubject(final Statistic.Type type, final String subject, final Server server) {
        return switch (type) {
            case BLOCK -> material(subject).filter(server::isBlock).isPresent();
            case ITEM -> material(subject).filter(server::isItem).isPresent();
            case ENTITY -> entity(subject).isPresent();
            case UNTYPED -> false;
        };
    }

    private static String kind(final Statistic.Type type) {
        return switch (type) {
            case BLOCK -> "block";
            case ITEM -> "item";
            case ENTITY -> "entity type";
            case UNTYPED -> "subject";
        };
    }

    private static List<String> advancementProblems(final Objective objective, final Server server) {
        if (objective.advancementKey().filter(server::hasAdvancement).isPresent()) {
            return List.of();
        }
        return List.of("names the advancement '" + objective.advancement() + "', which this server does not"
                + " have. Advancements are keys, such as minecraft:story/mine_diamond.");
    }
}
