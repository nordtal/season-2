package eu.nordtal.s2.steward.worker.configfile;

/**
 * Where a key and its value sit in a config file, 0-based.
 *
 * The key's own position is carried as well as the value's, because rewriting a block starts at the key's
 * indentation and not at the value's. {@code start == end} is the empty span of a key with no value ({@code token:}),
 * sitting directly after the colon.
 *
 * @param keyLine the line the key is on
 * @param keyColumn the column the key starts at, which is the indentation everything belonging to it has to be
 *     deeper than
 * @param keyEndColumn one past the key's last character, so the colon can be found without guessing at what the key
 *     itself contains
 * @param line the line the value starts on: the key's line for a scalar and for a flow sequence, the line of the
 *     first {@code -} for a block sequence
 * @param start the column the value starts at: the {@code |} of a block scalar, the {@code [} of a flow sequence,
 *     the {@code -} of a block one
 * @param end the column one past the value, on {@link #endLine}
 * @param endLine where SnakeYAML's end mark sits, which for anything written as a block is the line after it. The
 *     writer measures a block by indentation rather than trusting this
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
