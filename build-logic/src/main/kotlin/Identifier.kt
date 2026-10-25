package eu.nordtal.season.build

/**
 * A resource location such as `nordtal:badges/star.png`, as `net.minecraft.resources.Identifier` of the client accepts it.
 *
 * The client allows `a-z 0-9 _ - .` in the namespace, except the namespace `..`, and also `/` in the path. A file under
 * `assets/` also needs every path segment to be a name, so empty segments are refused, and so is a namespace or segment
 * that ends in `.`, which Windows drops from a file name.
 */
object Identifier {
    private val NAMESPACE = Regex("""[a-z0-9_.-]+""")
    private val SEGMENT = Regex("""[a-z0-9_.-]+""")

    /** Why [id] is no identifier with an explicit namespace, or null when it is one. */
    fun problem(id: String): String? {
        val namespace = id.substringBefore(':', "")
        val path = id.substringAfter(':', "")
        val segments = path.split('/')
        return when {
            id.count { it == ':' } != 1 -> {
                "$id is not namespace:path, with exactly one colon"
            }

            !NAMESPACE.matches(namespace) -> {
                "the namespace of $id may hold only a-z, 0-9, _, - and ."
            }

            namespace.endsWith('.') -> {
                "the namespace of $id ends in ., which Windows drops from a folder name"
            }

            segments.any { !SEGMENT.matches(it) } -> {
                "the path of $id may hold only a-z, 0-9, _, -, . and /"
            }

            segments.any { it.endsWith('.') } -> {
                "a name in the path of $id ends in ., which Windows drops from a file name"
            }

            else -> {
                null
            }
        }
    }

    /**
     * Returns [id] split into namespace and path.
     *
     * @throws IllegalArgumentException if [id] is no identifier, or does not end in [suffix]
     */
    fun parse(
        id: String,
        suffix: String = "",
    ): Pair<String, String> {
        problem(id)?.let { throw IllegalArgumentException("${it.replaceFirstChar(Char::uppercase)}.") }
        require(
            id.endsWith(suffix) && id.substringAfterLast('/').substringAfter(':') != suffix,
        ) { "$id does not end in a name and $suffix." }
        return id.substringBefore(':') to id.substringAfter(':')
    }
}
