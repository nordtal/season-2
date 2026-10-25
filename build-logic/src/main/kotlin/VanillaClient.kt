package eu.nordtal.season.build

import groovy.json.JsonSlurper
import java.io.DataInputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * The client jar of one Minecraft version from Mojang's version manifest, and the textures in it.
 *
 * The jar is a starting point for a vanilla override. It is never committed and never part of the pack.
 */
object VanillaClient {
    /** Mojang's version manifest, which names every version's JSON and its SHA-1. */
    const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

    private val TEXTURE = Regex("""assets/([a-z0-9_.-]+)/textures/([a-z0-9_./-]+\.png)""")

    private val http: HttpClient by lazy { HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build() }

    /**
     * Returns `<cache>/<version>/client.jar`, downloaded once and checked against the SHA-1 that the manifest names.
     * A later call reads no network while the jar still has the SHA-1 written beside it.
     */
    fun jar(
        version: String,
        manifest: URI,
        cache: File,
    ): File {
        val folder = cache.resolve(version)
        val jar = folder.resolve("client.jar")
        val stored = folder.resolve("client.jar.sha1")
        if (jar.isFile && stored.isFile && sha1(jar.readBytes()) == stored.readText().trim()) return jar

        val versions = (json(get(manifest)) as Map<*, *>)["versions"] as List<*>
        val entry =
            versions.filterIsInstance<Map<*, *>>().firstOrNull { it["id"] == version }
                ?: throw IllegalStateException("Mojang's version manifest lists no Minecraft $version.")
        val details = checked(get(URI(entry["url"] as String)), entry["sha1"] as String, "the JSON of Minecraft $version")
        val client = ((json(details) as Map<*, *>)["downloads"] as Map<*, *>)["client"] as Map<*, *>
        val bytes = checked(get(URI(client["url"] as String)), client["sha1"] as String, "the client jar of Minecraft $version")

        folder.mkdirs()
        val partial = File.createTempFile("client", ".jar.part", folder)
        partial.writeBytes(bytes)
        Files.move(partial.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        stored.writeText(client["sha1"] as String + "\n")
        return jar
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
        val path = "assets/${id.substringBefore(':')}/textures/${id.substringAfter(':')}"
        require(
            ':' in id && TEXTURE.matches(path) && ".." !in path.split('/'),
        ) { "$id is not a texture id such as minecraft:block/stone.png." }
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

    private fun get(uri: URI): ByteArray {
        val response = http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() == 200) { "$uri answered ${response.statusCode()}." }
        return response.body()
    }

    private fun checked(
        bytes: ByteArray,
        expected: String,
        what: String,
    ): ByteArray {
        val actual = sha1(bytes)
        check(actual == expected) { "The SHA-1 of $what is $actual, but Mojang's manifest names $expected." }
        return bytes
    }

    private fun json(bytes: ByteArray): Any? = JsonSlurper().parse(bytes.inputStream())
}
