package eu.nordtal.s2.steward.configfile;

/**
 * Where a key and its value sit in a config file, 0-based.
 *
 * @param keyLine the line the key is on
 * @param keyColumn the column the key starts at, which everything belonging to it is deeper than
 * @param keyEndColumn one past the key's last character, so the colon can be found
 * @param line the line the value starts on: the key's line, or the first entry's for a block sequence
 * @param start the column the value starts at; equal to {@code end} for a key with no value
 * @param end the column one past the value, on {@link #endLine}
 * @param endLine SnakeYAML's end mark, the line after a block, which the writer does not trust
 * @param flow whether a sequence is written {@code [a, b]} rather than as a block
 * @param block whether a scalar is written {@code |} or {@code >} rather than inline
 */
record Span(
        int keyLine,
        int keyColumn,
        int keyEndColumn,
        int line,
        int start,
        int end,
        int endLine,
        boolean flow,
        boolean block) {

    boolean multiLine() {
        return endLine > line;
    }

    /** Whether the value's characters start on the key's own line. */
    boolean onKeyLine() {
        return line == keyLine;
    }
}
