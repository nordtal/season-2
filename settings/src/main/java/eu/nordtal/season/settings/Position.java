package eu.nordtal.season.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/**
 * Marks a nested spec, or a list of them, as a place in a world, which Steward draws as one field.
 * Steward fills it from where the admin's player stands or looks. The spec holds the keys of its {@link Shape}.
 * The schema bounds each coordinate by these limits, unless the field declares a bound of its own.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Position {

    /** The farthest a block's X or Z lies from the centre of a world: the edge of its largest border. */
    long FARTHEST = 30_000_000L;

    /** The lowest Y a world's floor can be set to. */
    long LOWEST = -2048L;

    /** The highest Y a block can have in a world of the greatest height. */
    long HIGHEST = 2031L;

    /** The bound of a facing to either side of south, in degrees. */
    long TURN = 180L;

    /** The bound of a look up or down, in degrees. */
    long TILT = 90L;

    Shape value();

    /** The keys a spec of each shape holds; it may hold other fields beside them, such as a board's kind. */
    enum Shape {
        /** Where a player stands and faces: the world, X, Y and Z, and the facing. A look up or down is optional. */
        SPOT,
        /** One block: the world and the block's X, Y and Z, taken from the block the admin looks at. */
        BLOCK,
        /** The area between two corner blocks in one world. */
        AREA,
        /** A column of the world, such as the centre of its border: X and Z. */
        CENTRE;

        public List<String> keys() {
            return switch (this) {
                case SPOT -> List.of("world", "x", "y", "z", "yaw");
                case BLOCK -> List.of("world", "x", "y", "z");
                case AREA -> List.of("world", "min-x", "min-y", "min-z", "max-x", "max-y", "max-z");
                case CENTRE -> List.of("x", "z");
            };
        }
    }
}
