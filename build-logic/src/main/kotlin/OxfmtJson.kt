package eu.nordtal.season.build

import java.io.File
import java.math.BigDecimal

/**
 * Reads and writes JSON in the layout that oxfmt checks with `.oxfmtrc.json`: 120 columns, two spaces of indent. The
 * files under the pack's `src/assets` keep four, which [format] takes as its `indent`.
 *
 * An object keeps its lines when the source breaks after its `{`, as oxfmt does, so a file read and written again
 * is unchanged. A designer's machine has no Node, so the tasks that edit `glyphs.json` write this layout themselves.
 */
object OxfmtJson {
    private const val WIDTH = 120
    private const val INDENT = "  "

    /**
     * A JSON object in the order of its source. [expanded] keeps one member per line, even where the object would fit,
     * and an empty line stays before each key in [spaced].
     */
    class Object(
        var expanded: Boolean = true,
    ) : LinkedHashMap<String, Any?>() {
        val spaced = mutableSetOf<String>()

        /** On the root of a document: the source spelling of each string whose escapes differ from the written ones. */
        val spellings = mutableMapOf<String, String>()
    }

    /** A JSON array in the order of its source. An empty line stays before each index in [spaced] while the array breaks. */
    class Array : ArrayList<Any?>() {
        val spaced = mutableSetOf<Int>()
        val spellings = mutableMapOf<String, String>()

        override fun add(
            index: Int,
            element: Any?,
        ) {
            super.add(index, element)
            shift(index) { it + 1 }
        }

        override fun removeAt(index: Int): Any? = super.removeAt(index).also { shift(index + 1) { it - 1 } }

        /** Moves each empty line from [from] on with its member, so that it stays before the same member. */
        private fun shift(
            from: Int,
            by: (Int) -> Int,
        ) {
            val moved = spaced.map { if (it >= from) by(it) else it }
            spaced.clear()
            spaced += moved
        }
    }

    fun read(file: File): Any? = read(file.readText())

    fun read(text: String): Any? = Parser(text).document()

    fun format(
        value: Any?,
        indent: String = INDENT,
    ): String {
        val spellings = (value as? Object)?.spellings ?: (value as? Array)?.spellings ?: emptyMap()
        return StringBuilder().also { Printer(spellings, indent).print(value, 0, 0, 0, it) }.append('\n').toString()
    }

    private class Printer(
        private val spellings: Map<String, String>,
        private val step: String,
    ) {
        /** Appends [value] at column [column] with [indent] levels, followed on its line by [trailing] characters. */
        fun print(
            value: Any?,
            indent: Int,
            column: Int,
            trailing: Int,
            out: StringBuilder,
        ) {
            if (value is Map<*, *> && value.isEmpty()) return out.append("{}").let { }
            if (value is List<*> && value.isEmpty()) return out.append("[]").let { }
            if (value !is Map<*, *> && value !is List<*>) return out.append(scalar(value)).let { }
            val flat = flat(value)
            if (!mustBreak(value) && column + flat.length + trailing <= WIDTH) return out.append(flat).let { }

            val inner = step.repeat(indent + 1)
            val members: List<Pair<String, Any?>> =
                if (value is Map<*, *>) {
                    value.map { (key, member) -> string(key as String) + ": " to member }
                } else {
                    (value as List<*>).map {
                        "" to
                            it
                    }
                }
            out.append(if (value is Map<*, *>) "{" else "[")
            val keys = (value as? Map<*, *>)?.keys?.toList()
            members.forEachIndexed { index, (prefix, member) ->
                val last = index == members.lastIndex
                val spaced = (value as? Object)?.spaced?.contains(keys!![index]) ?: (value as? Array)?.spaced?.contains(index) ?: false
                if (spaced && index > 0) out.append('\n')
                out.append('\n').append(inner).append(prefix)
                print(member, indent + 1, inner.length + prefix.length, if (last) 0 else 1, out)
                if (!last) out.append(',')
            }
            out.append('\n').append(step.repeat(indent)).append(if (value is Map<*, *>) "}" else "]")
        }

        /** Whether oxfmt breaks [value] whatever its width: an expanded object, or an array of objects or arrays with two or more members each. */
        private fun mustBreak(value: Any?): Boolean =
            when (value) {
                is Object -> {
                    value.isNotEmpty() &&
                        (value.expanded || (value.spaced - value.keys.first()).any { it in value } || value.values.any { mustBreak(it) })
                }

                is Map<*, *> -> {
                    value.values.any { mustBreak(it) }
                }

                is List<*> -> {
                    value.any { mustBreak(it) } ||
                        (value.size > 1 && (value.all { it is Map<*, *> && it.size > 1 } || value.all { it is List<*> && it.size > 1 }))
                }

                else -> {
                    false
                }
            }

        private fun flat(value: Any?): String =
            when {
                value is Map<*, *> && value.isEmpty() -> "{}"
                value is Map<*, *> -> value.entries.joinToString(", ", "{ ", " }") { string(it.key as String) + ": " + flat(it.value) }
                value is List<*> -> value.joinToString(", ", "[", "]") { flat(it) }
                else -> scalar(value)
            }

        private fun scalar(value: Any?): String =
            when (value) {
                null -> "null"
                is String -> string(value)
                is Boolean, is Int, is Long, is BigDecimal -> value.toString()
                else -> throw IllegalArgumentException("${value::class.simpleName} is no JSON value")
            }

        private fun string(text: String): String = spellings[text] ?: escaped(text)
    }

    private fun escaped(text: String): String =
        buildString {
            append('"')
            for (char in text) {
                when {
                    char == '"' -> append("\\\"")
                    char == '\\' -> append("\\\\")
                    char == '\n' -> append("\\n")
                    char == '\t' -> append("\\t")
                    char == '\r' -> append("\\r")
                    char < ' ' -> append("\\u%04x".format(char.code))
                    else -> append(char)
                }
            }
            append('"')
        }

    private class Parser(
        private val text: String,
    ) {
        private var at = 0
        private val spellings = mutableMapOf<String, String>()

        fun document(): Any? {
            val root = value()
            space()
            if (at != text.length) fail("text after the value")
            (root as? Object)?.spellings?.putAll(spellings)
            (root as? Array)?.spellings?.putAll(spellings)
            return root
        }

        private fun value(): Any? {
            space()
            return when (val char = text.getOrNull(at)) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (char == '-' || char?.isDigit() == true) number() else fail("a value")
            }
        }

        private fun obj(): Object {
            at++
            val result = Object(expanded = text.substring(at, ahead()).contains('\n'))
            space()
            if (text.getOrNull(at) == '}') return result.also { at++ }
            while (true) {
                val spaced = blank()
                space()
                if (text.getOrNull(at) != '"') fail("a key")
                val key = string()
                if (spaced) result.spaced += key
                space()
                expect(':')
                result[key] = value()
                space()
                if (text.getOrNull(at) == '}') return result.also { at++ }
                expect(',')
            }
        }

        private fun array(): Array {
            at++
            val result = Array()
            space()
            if (text.getOrNull(at) == ']') return result.also { at++ }
            while (true) {
                if (blank()) result.spaced += result.size
                result += value()
                space()
                if (text.getOrNull(at) == ']') return result.also { at++ }
                expect(',')
            }
        }

        private fun string(): String {
            val start = at++
            val result = StringBuilder()
            while (true) {
                val char = text.getOrNull(at++) ?: fail("the end of a string")
                when (char) {
                    '"' -> {
                        val decoded = result.toString()
                        val spelling = text.substring(start, at)
                        if (spelling != escaped(decoded)) spellings.putIfAbsent(decoded, spelling)
                        return decoded
                    }

                    '\\' -> {
                        when (val escaped = text.getOrNull(at++)) {
                            'u' -> result.append(text.substring(at, at + 4).toInt(16).toChar()).also { at += 4 }
                            'n' -> result.append('\n')
                            't' -> result.append('\t')
                            'r' -> result.append('\r')
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            '"', '\\', '/' -> result.append(escaped)
                            else -> fail("an escape")
                        }
                    }

                    else -> {
                        result.append(char)
                    }
                }
            }
        }

        private fun number(): Any {
            val start = at
            while (at < text.length && text[at] in "+-0123456789.eE") at++
            val raw = text.substring(start, at)
            return raw.toIntOrNull() ?: raw.toLongOrNull() ?: BigDecimal(raw)
        }

        private fun word(
            word: String,
            value: Any?,
        ): Any? {
            if (!text.startsWith(word, at)) fail(word)
            at += word.length
            return value
        }

        private fun space() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        /** Whether the white space ahead holds an empty line. */
        private fun blank(): Boolean = text.substring(at, ahead()).count { it == '\n' } > 1

        /** The index of the next character that is not white space. */
        private fun ahead(): Int {
            var index = at
            while (index < text.length && text[index].isWhitespace()) index++
            return index
        }

        private fun expect(char: Char) {
            if (text.getOrNull(at) != char) fail("'$char'")
            at++
        }

        private fun fail(expected: String): Nothing {
            val line = text.substring(0, at.coerceAtMost(text.length)).count { it == '\n' } + 1
            throw IllegalArgumentException("Line $line of the JSON holds no $expected.")
        }
    }
}
