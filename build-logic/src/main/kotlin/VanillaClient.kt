package eu.nordtal.season.build

import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * The textures and item definitions in the season's client jar, which Fabric Loom downloads for `:bridge-mod`.
 *
 * The jar is a starting point for a vanilla override. It is never committed and never part of the pack.
 */
object VanillaClient {
    private val TEXTURE = Regex("""assets/([a-z0-9_.-]+)/textures/([a-z0-9_./-]+\.png)""")
    private val ITEM = Regex("""assets/([a-z0-9_.-]+)/items/([a-z0-9_./-]+)\.json""")

    /** Lists the id of every item definition in [jar], such as `minecraft:compass`, sorted. */
    fun items(jar: File): List<String> =
        ZipFile(jar).use { zip ->
            zip
                .entries()
                .asSequence()
                .mapNotNull { ITEM.matchEntire(it.name) }
                .map { "${it.groupValues[1]}:${it.groupValues[2]}" }
                .sorted()
                .toList()
        }

    /** Lists every texture in [jar] by its id, such as `minecraft:block/stone.png`, with its pixels and whether it has a `.mcmeta`. */
    fun textures(jar: File): List<Map<String, Any>> =
        ZipFile(jar).use { zip ->
            val names =
                zip
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .toSet()
            names
                .mapNotNull { name -> TEXTURE.matchEntire(name)?.let { name to it } }
                .sortedBy { it.first }
                .map { (name, match) ->
                    DataInputStream(zip.getInputStream(zip.getEntry(name))).use { png ->
                        // The IHDR chunk follows the 8-byte signature and its own 8-byte header.
                        png.skipNBytes(16)
                        linkedMapOf(
                            "id" to "${match.groupValues[1]}:${match.groupValues[2]}",
                            "width" to png.readInt(),
                            "height" to png.readInt(),
                            "mcmeta" to ("$name.mcmeta" in names),
                        )
                    }
                }
        }

    /**
     * Copies the texture [id] and its `.mcmeta` from [jar] to its override path under [assets], and returns the files written.
     *
     * @throws IllegalArgumentException if the jar has no texture [id]
     * @throws IllegalStateException if the override exists already
     */
    fun copy(
        jar: File,
        id: String,
        assets: File,
    ): List<File> {
        val (namespace, file) = Identifier.parse(id, ".png")
        val path = "assets/$namespace/textures/$file"
        return ZipFile(jar).use { zip ->
            zip.getEntry(path) ?: throw IllegalArgumentException("The client jar has no texture $id.")
            val target = assets.resolve(path.removePrefix("assets/"))
            check(!target.exists()) { "${target.path} exists already. Delete it first to copy the vanilla texture again." }
            listOf(path, "$path.mcmeta").mapNotNull { name ->
                val entry = zip.getEntry(name) ?: return@mapNotNull null
                assets.resolve(name.removePrefix("assets/")).also { file ->
                    file.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                }
            }
        }
    }
}
