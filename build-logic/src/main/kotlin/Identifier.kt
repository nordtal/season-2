package eu.nordtal.season.build

/**
 * A resource location such as `nordtal:badges/star.png`, as `net.minecraft.resources.Identifier` of the client accepts it.
 *
 * The client allows `a-z 0-9 _ - .` in the namespace, except the namespace `..`, and also `/` in the path. A file under
 * `assets/` also needs every path segment to be a name, so empty, `.` and `..` segments are refused too.
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

            !NAMESPACE.matches(namespace) || namespace == "." || namespace == ".." -> {
                "the namespace of $id may hold only a-z, 0-9, _, - and ., and is not . or .."
            }

            segments.any { !SEGMENT.matches(it) } -> {
                "the path of $id may hold only a-z, 0-9, _, -, . and /"
            }

            segments.any { it == "." || it == ".." } -> {
                "the path of $id has a . or .. folder"
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
