package eu.nordtal.s2.database.update;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;

import eu.nordtal.s2.messages.MessageRef;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A number of bytes as a person reads it: whole bytes below a KiB, else one decimal in the largest binary unit.
 *
 * @param amount the size in {@code unit}
 * @param unit   the binary unit, which a message names through {@code select}
 */
public record ByteSize(BigDecimal amount, ByteSize.Unit unit) {

    /** The binary units, smallest first. */
    public enum Unit {
        B("B"),
        KIB("KiB"),
        MIB("MiB"),
        GIB("GiB"),
        TIB("TiB");

        private final String symbol;

        Unit(final String symbol) {
            this.symbol = symbol;
        }
    }

    public static ByteSize of(final long bytes) {
        if (bytes < 1024) {
            return new ByteSize(BigDecimal.valueOf(bytes), Unit.B);
        }
        final Unit[] units = Unit.values();
        double value = bytes / 1024.0;
        int unit = 1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return new ByteSize(BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP), units[unit]);
    }

    /** The size as a message of the report section, for a report line that names one. */
    public MessageRef message() {
        return TEXTS.report().size(amount, unit);
    }

    /** The size for a log line or a file listing, such as {@code 2.0 KiB}. */
    @Override
    public String toString() {
        return amount.toPlainString() + " " + unit.symbol;
    }
}
