package eu.nordtal.season.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks what the value of a setting names: an entry of a game registry, a colour or a Discord object.
 * Steward offers a picker for it. On a list every entry names one. The value stays text, which the game checks.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Refers {

    To value();

    /** The key of the sibling whose value decides the registry. {@link To#SUBJECT} needs it. */
    String dependsOn() default "";

    /** Whether an empty value is a choice of its own, such as no channel at all. */
    boolean optional() default false;

    String[] except() default {};

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
        COLOUR,
        DISCORD_CHANNEL,
        DISCORD_USER,
        /** A world the setting's own server had loaded when it last started, by name. */
        WORLD
    }
}
