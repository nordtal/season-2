package eu.nordtal.season.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What a setting's value names: an entry of a game registry, a colour or a Discord object, which Steward picks.
 *
 * On a list every entry names one. The value stays text the game checks; Steward never refuses an unknown one.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Refers {

    /** What the value names. */
    To value();

    /** The key of a sibling whose value decides the registry, which {@link To#SUBJECT} needs. */
    String dependsOn() default "";

    /** Whether an empty value is a choice of its own, such as no channel at all. */
    boolean optional() default false;

    /** The entries of the registry a picker leaves out, by their namespaced key. */
    String[] except() default {};

    /** What a value names; a registry's constant is that registry's name in the game catalogue, lower case. */
    enum To {
        ITEM,
        BLOCK,
        ENTITY_TYPE,
        ADVANCEMENT,
        STATISTIC,
        /** An item, block or entity type, whichever the statistic named by {@link Refers#dependsOn} counts. */
        SUBJECT,
        ENCHANTMENT,
        BIOME,
        MOB_EFFECT,
        SOUND_EVENT,
        DAMAGE_TYPE,
        /** A colour as {@code #rrggbb}. */
        COLOUR,
        DISCORD_CHANNEL,
        DISCORD_USER
    }
}
